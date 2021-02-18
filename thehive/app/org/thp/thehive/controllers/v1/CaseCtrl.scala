package org.thp.thehive.controllers.v1

import org.apache.tinkerpop.gremlin.structure.Graph
import org.thp.scalligraph.controllers.{Entrypoint, FieldsParser}
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.query.{ParamQuery, PropertyUpdater, PublicProperties, Query}
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{IteratorOutput, Traversal}
import org.thp.scalligraph.{BadRequestError, EntityIdOrName, RichOptionTry, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.dto.v1.{InputCase, InputTask}
import org.thp.thehive.models._
import org.thp.thehive.services.AlertOps._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.CaseTemplateOps._
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.ShareOps._
import org.thp.thehive.services.UserOps._
import org.thp.thehive.services._
import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.{Inject, Named, Singleton}
import scala.util.{Failure, Success, Try}

@Singleton
class CaseCtrl @Inject() (
    entrypoint: Entrypoint,
    properties: Properties,
    caseSrv: CaseSrv,
    caseTemplateSrv: CaseTemplateSrv,
    userSrv: UserSrv,
    tagSrv: TagSrv,
    organisationSrv: OrganisationSrv,
    @Named("with-thehive-schema") implicit val db: Database
) extends QueryableCtrl
    with CaseRenderer {

  override val entityName: String                 = "case"
  override val publicProperties: PublicProperties = properties.`case`
  override val initialQuery: Query =
    Query.init[Traversal.V[Case]]("listCase", (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).cases)
  override val getQuery: ParamQuery[EntityIdOrName] = Query.initWithParam[EntityIdOrName, Traversal.V[Case]](
    "getCase",
    FieldsParser[EntityIdOrName],
    (idOrName, graph, authContext) => caseSrv.get(idOrName)(graph).visible(authContext)
  )
  override val pageQuery: ParamQuery[OutputParam] = Query.withParam[OutputParam, Traversal.V[Case], IteratorOutput](
    "page",
    FieldsParser[OutputParam],
    {
      case (OutputParam(from, to, extraData), caseSteps, authContext) =>
        caseSteps.richPage(from, to, extraData.contains("total")) {
          _.richCaseWithCustomRenderer(caseStatsRenderer(extraData - "total")(authContext))(authContext)
        }
    }
  )
  override val outputQuery: Query = Query.outputWithContext[RichCase, Traversal.V[Case]]((caseSteps, authContext) => caseSteps.richCase(authContext))
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    Query[Traversal.V[Case], Traversal.V[Task]]("tasks", (caseSteps, authContext) => caseSteps.tasks(authContext)),
    Query[Traversal.V[Case], Traversal.V[Observable]]("observables", (caseSteps, authContext) => caseSteps.observables(authContext)),
    Query[Traversal.V[Case], Traversal.V[User]]("assignableUsers", (caseSteps, authContext) => caseSteps.assignableUsers(authContext)),
    Query[Traversal.V[Case], Traversal.V[Organisation]]("organisations", (caseSteps, authContext) => caseSteps.organisations.visible(authContext)),
    Query[Traversal.V[Case], Traversal.V[Alert]]("alerts", (caseSteps, authContext) => caseSteps.alert.visible(authContext)),
    Query[Traversal.V[Case], Traversal.V[Share]]("shares", (caseSteps, authContext) => caseSteps.shares.visible(authContext)),
    Query[Traversal.V[Case], Traversal.V[Procedure]]("procedures", (caseSteps, _) => caseSteps.procedure)
  )

  def create: Action[AnyContent] =
    entrypoint("create case")
      .extract("case", FieldsParser[InputCase])
      .extract("caseTemplate", FieldsParser[String].optional.on("caseTemplate"))
      .extract("tasks", FieldsParser[InputTask].sequence.on("tasks"))
      .authTransaction(db) { implicit request => implicit graph =>
        val caseTemplateName: Option[String] = request.body("caseTemplate")
        val inputCase: InputCase             = request.body("case")
        val inputTasks: Seq[InputTask]       = request.body("tasks")
        for {
          caseTemplate <- caseTemplateName.map(ct => caseTemplateSrv.get(EntityIdOrName(ct)).visible.richCaseTemplate.getOrFail("CaseTemplate")).flip
          organisation <- userSrv.current.organisations(Permissions.manageCase).get(request.organisation).getOrFail("Organisation")
          user         <- inputCase.user.fold[Try[Option[User with Entity]]](Success(None))(u => userSrv.getOrFail(EntityIdOrName(u)).map(Some.apply))
          tags         <- inputCase.tags.toTry(tagSrv.getOrCreate)
          richCase <- caseSrv.create(
            caseTemplate.fold(inputCase)(inputCase.withCaseTemplate).toCase,
            user,
            organisation,
            tags.toSet,
            inputCase.customFieldValues,
            caseTemplate,
            inputTasks.map(t => t.toTask -> t.assignee.flatMap(u => userSrv.get(EntityIdOrName(u)).headOption))
          )
        } yield Results.Created(richCase.toJson)
      }

  def get(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("get case")
      .authRoTransaction(db) { implicit request => implicit graph =>
        caseSrv
          .get(EntityIdOrName(caseIdOrNumber))
          .visible
          .richCase
          .getOrFail("Case")
          .map(richCase => Results.Ok(richCase.toJson))
      }

  def update(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("update case")
      .extract("case", FieldsParser.update("case", publicProperties))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("case")
        caseSrv
          .update(_.get(EntityIdOrName(caseIdOrNumber)).can(Permissions.manageCase), propertyUpdaters)
          .map(_ => Results.NoContent)
      }

  def delete(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("delete case")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          c <-
            caseSrv
              .get(EntityIdOrName(caseIdOrNumber))
              .can(Permissions.manageCase)
              .getOrFail("Case")
          _ <- caseSrv.remove(c)
        } yield Results.NoContent
      }

  def merge(caseIdsOrNumbers: String): Action[AnyContent] =
    entrypoint("merge cases")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          cases <-
            caseIdsOrNumbers
              .split(',')
              .toSeq
              .toTry(c =>
                caseSrv
                  .get(EntityIdOrName(c))
                  .visible
                  .getOrFail("Case")
              )
          _          <- sameOrga(cases)
          _          <- sameProfile(cases)
          mergedCase <- caseSrv.merge(cases)
        } yield Results.Ok(mergedCase.toJson)
      }

  private def sameOrga(cases: Seq[Case with Entity])(implicit graph: Graph): Try[Seq[Case with Entity]] =
    for {
      orgas <- cases.toTry(c => caseSrv.get(c).organisations.getOrFail("Organisation"))
      firstOrga <- orgas.headOption match {
        case Some(o) => Success(o)
        case None    => Failure(BadRequestError("No organisations found"))
      }
      sameOrga = orgas.forall(_.name == firstOrga.name)
      res <- if (sameOrga) Success(cases) else Failure(BadRequestError(s"Cases to merge have different organisations"))
    } yield res

  private def sameProfile(cases: Seq[Case with Entity])(implicit graph: Graph): Try[Seq[Case with Entity]] =
    for {
      profiles <- cases.toTry(c => caseSrv.get(c).shares.profile.getOrFail("Profile"))
      firstProfile <- profiles.headOption match {
        case Some(o) => Success(o)
        case None    => Failure(BadRequestError("No profiles found"))
      }
      sameProfile = profiles.forall(_.name == firstProfile.name)
      res <- if (sameProfile) Success(cases) else Failure(BadRequestError(s"Cases to merge have different profiles"))
    } yield res
}
