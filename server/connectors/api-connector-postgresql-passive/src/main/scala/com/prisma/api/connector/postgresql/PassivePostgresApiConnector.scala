package com.prisma.api.connector.postgresql

import java.sql.PreparedStatement

import com.prisma.api.connector._
import com.prisma.api.connector.postgresql.database.{Databases, PostGresApiDatabaseMutationBuilder}
import com.prisma.api.connector.postgresql.impl._
import com.prisma.config.DatabaseConfig
import com.prisma.gc_values.{IdGCValue, NullGCValue, RootGCValue}
import com.prisma.shared.models.Manifestations.InlineRelationManifestation
import com.prisma.shared.models.{Project, ProjectIdEncoder}
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.TransactionIsolation

import scala.concurrent.{ExecutionContext, Future}

case class PassivePostgresApiConnector(config: DatabaseConfig)(implicit ec: ExecutionContext) extends ApiConnector {
  lazy val databases = Databases.initialize(config)

  val activeConnector = PostgresApiConnector(config)

  override def initialize() = {
    databases
    Future.unit
  }

  override def shutdown() = {
    for {
      _ <- activeConnector.shutdown()
      _ <- databases.master.shutdown
      _ <- databases.readOnly.shutdown
    } yield ()
  }

  override def databaseMutactionExecutor: DatabaseMutactionExecutor =
    PassiveDatabaseMutactionExecutorImpl(activeConnector.databaseMutactionExecutor.asInstanceOf[DatabaseMutactionExecutorImpl])
  override def dataResolver(project: Project)       = activeConnector.dataResolver(project)
  override def masterDataResolver(project: Project) = activeConnector.masterDataResolver(project)

  override def projectIdEncoder: ProjectIdEncoder = activeConnector.projectIdEncoder

  override def capabilities = Vector.empty
}

case class PassiveDatabaseMutactionExecutorImpl(activeExecutor: DatabaseMutactionExecutorImpl)(implicit ec: ExecutionContext)
    extends DatabaseMutactionExecutor {

  override def execute(mutactions: Vector[DatabaseMutaction], runTransactionally: Boolean): Future[Vector[DatabaseMutactionResult]] = {
    val transformed         = transform(mutactions)
    val interpreters        = transformed.map(interpreterFor)
    val combinedErrorMapper = interpreters.map(_.errorMapper).reduceLeft(_ orElse _)

    val singleAction = runTransactionally match {
      case true  => DBIO.sequence(interpreters.map(_.newAction)).transactionally
      case false => DBIO.sequence(interpreters.map(_.newAction))
    }

    activeExecutor.clientDb
      .run(singleAction.withTransactionIsolation(TransactionIsolation.ReadCommitted))
      .recover { case error => throw combinedErrorMapper.lift(error).getOrElse(error) }
  }

  def transform(mutactions: Vector[DatabaseMutaction]): Vector[PassiveDatabaseMutaction] = {
    val replacements: Map[DatabaseMutaction, PassiveDatabaseMutaction] = mutactions
      .collect {
        case candidate: CreateDataItem =>
          val partner: Option[NestedCreateRelation] = mutactions.collectFirst {
            case m: NestedCreateRelation if m.path == candidate.path && m.path.lastRelation_!.isInlineRelation => m
          }
          partner.map { p =>
            candidate -> NestedCreateDataItem(candidate, p)
          }
      }
      .collect {
        case Some(x) => x
      }
      .toMap
    val removals: Vector[DatabaseMutaction] = replacements.values.toVector.flatMap(_.replaces)
    mutactions.collect {
      //case m if removals.contains(m) => N
      case m if replacements.contains(m) => replacements(m)
      case m if !removals.contains(m)    => PlainActiveDatabaseMutaction(m)
    }
  }

  def interpreterFor(mutaction: PassiveDatabaseMutaction): DatabaseMutactionInterpreter = mutaction match {
    case PlainActiveDatabaseMutaction(m: CreateDataItem) => CreateDataItemInterpreter(m, includeRelayRow = false)
    case PlainActiveDatabaseMutaction(m)                 => activeExecutor.interpreterFor(m)
    case m: NestedCreateDataItem                         => NestedCreateDataItemInterpreterForInlineRelations(m)
  }
}

sealed trait PassiveDatabaseMutaction {
  def replaces: Vector[DatabaseMutaction]
}
case class PlainActiveDatabaseMutaction(databaseMutaction: DatabaseMutaction) extends PassiveDatabaseMutaction {
  override def replaces = Vector.empty
}
case class NestedCreateDataItem(create: CreateDataItem, nestedCreateRelation: NestedCreateRelation) extends PassiveDatabaseMutaction {
  override def replaces = Vector(create, nestedCreateRelation)
}

case class NestedCreateDataItemInterpreterForInlineRelations(mutaction: NestedCreateDataItem) extends DatabaseMutactionInterpreter {
  import scala.concurrent.ExecutionContext.Implicits.global

  val project  = mutaction.create.project
  val path     = mutaction.create.path
  val relation = mutaction.nestedCreateRelation.path.lastRelation_!
  val model    = mutaction.create.path.lastModel

  require(relation.isInlineRelation)
  val inlineManifestation = relation.manifestation.get.asInstanceOf[InlineRelationManifestation]
  require(inlineManifestation.inTableOfModelId == path.lastModel.id)

  override val action = {
//    val createNonList = PostGresApiDatabaseMutationBuilder.createDataItem(project.id, path, mutaction.create.nonListArgs)
//    val listAction    = PostGresApiDatabaseMutationBuilder.setScalarList(project.id, path, mutaction.listArgs)
    DBIO.seq(bla)
  }

  import com.prisma.api.connector.postgresql.database.JdbcExtensions._
  import com.prisma.api.connector.postgresql.database.SlickExtensions._

  def bla = {
    val idSubQuery      = PostGresApiDatabaseMutationBuilder.pathQueryForLastParent(project.id, path)
    val lastParentModel = path.removeLastEdge.lastModel
    for {
      ids    <- (sql"""select "id" from #${project.id}.#${lastParentModel.dbName} where id in (""" ++ idSubQuery ++ sql")").as[String]
      result <- createDataItemAndLinkToParent2(ids.head)
    } yield result
  }

  def createDataItemAndLinkToParent2(parentId: String) = {
    val inlineField  = relation.getFieldOnModel(model.id, project.schema).get
    val argsMap      = mutaction.create.nonListArgs.raw.asRoot.map
    val modifiedArgs = argsMap.updated(inlineField.name, IdGCValue(parentId))
    PostGresApiDatabaseMutationBuilder.createDataItem(project.id, path, PrismaArgs(RootGCValue(modifiedArgs)))
  }
}
