package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.users.Username

import sttp.client4.Backend

import munit.FunSuite

import scala.concurrent.Future

/** The fixtures the organisation group's suites share, on top of the module-wide [[ClientSuiteHarness]].
  *
  * The stub backend, the pipeline and the request assertions live in [[ClientSuiteHarness]], which every API suite in
  * this module mixes in. What is left here is only what is specific to the organisation surface: the organisation, the
  * team and the account every suite in the group addresses, the `onApi` that builds [[OrganizationApi]], and the error
  * bodies they stub.
  */
trait OrganizationStubs extends ClientSuiteHarness:
  self: FunSuite =>

  /** The organisation every suite addresses, matching `golden/organization/org-single.json`. */
  val Org: OrgName = orFail(OrgName.from("forgejo"))

  /** The team every suite addresses. No capture exists for one; see [[Team]]. */
  val Maintainers: TeamId = orFail(TeamId.from(42L))

  /** The account every suite names, matching `golden/error/401-user-orgs.json`. */
  val Account: Username = orFail(Username.from("earl-warren"))

  /** Builds [[OrganizationApi]] on a pipeline over `backend`, releasing the timer whatever happens. */
  def onApi[A](backend: Backend[Future])(use: OrganizationApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(OrganizationApi(pipeline)))

/** The error bodies the organisation group's suites share. */
object OrganizationStubs:

  /** The body Forgejo returns to an anonymous caller on a route that needs a token; `golden/error/401-org-teams.json`
    * and `golden/error/401-token-required.json` are this shape.
    */
  val UnauthorizedBody: String = """{"message":"token is required","url":"https://codeberg.org/api/swagger"}"""

  /** A `404` shaped like `golden/error/404-repo-not-found.json`: a Go symbol for a message, the useful text in
    * `errors`.
    */
  val NotFoundBody: String =
    """{"message":"GetOrgByName","url":"https://codeberg.org/api/swagger",""" +
      """"errors":["organization does not exist [name: forgejo]"]}"""
