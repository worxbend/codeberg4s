package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.client.FutureExec
import com.worxbend.codeberg4s.client.FutureTimer
import com.worxbend.codeberg4s.codec.ApiErrorBodyCodec
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.organizations.TeamPermission
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.transport.SttpHttpPort
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.Username

import sttp.client4.Backend
import sttp.client4.Response
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.client4.testing.StubBody
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/** [[RepositoryAccessApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters and which body are sent, which calls may be
  * repeated, and what each rail does with a failure. Decoding itself is asserted in `modules/codec`, so the payloads
  * here are small hand-written bodies chosen to exercise a seam.
  *
  * '''The retry assertions are the point of this file.''' This group is the only one in the library whose `DELETE`
  * decisions come out both ways, and each of those decisions is a judgement about what a lost response may be allowed
  * to do to an access rule. A test that only checked paths would let any of them be silently reversed.
  *
  * '''No golden fixture backs this group.''' Every payload below was written from `spec/swagger.v1.json`; see the class
  * note on [[RepositoryAccessApi]].
  */
final class RepositoryAccessApiSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  private val Rule: BranchRuleName = orFail(BranchRuleName.from("main"))

  private val TagRule: TagProtectionId = orFail(TagProtectionId.from(17L))

  private val Key: DeployKeyId = orFail(DeployKeyId.from(4L))

  private val Collaborator: Username = orFail(Username.from("alice"))

  private val Squad: TeamName = orFail(TeamName.from("owners"))

  private val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  private val Root: String = "https://forge.example/api/v1/repos/forgejo/forgejo"

  // --- branch protections ---------------------------------------------------

  test("repos.branchProtections.list is a bare array and takes no paging parameters"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.BranchListBody))

    onBackend(backend): api =>
      api.listBranchProtections(Handle, Name).map: rules =>
        assertEquals(pathOf(backend), s"$Root/branch_protections")
        assertEquals(queryOf(backend), Nil)
        assertEquals(rules.map(_.ruleName), Vector("main"))
        assertEquals(rules.map(_.requireSignedCommits), Vector(true))

  test("a single-rule read addresses the rule by its own name"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.BranchBody))

    onBackend(backend): api =>
      api.branchProtection(Handle, Name, Rule).map: rule =>
        assertEquals(pathOf(backend), s"$Root/branch_protections/main")
        assertEquals(rule.ruleName, "main")

  test("repos.branchProtections.create POSTs the rendered options"):
    val backend = RecordingBackend(responding(201, RepositoryAccessApiSuite.BranchBody))
    val command = CreateBranchProtection
      .on(Rule)
      .withSettings(BranchProtectionSettings.Unchanged.requiringSignedCommits(true).applyingToAdmins(true))

    onBackend(backend): api =>
      api.createBranchProtection(Handle, Name, command).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/branch_protections")
        assertEquals(bodyOf(backend), """{"rule_name":"main","require_signed_commits":true,"apply_to_admins":true}""")

  test("repos.branchProtections.create is never retried, because this library repeats no POST"):
    val backend = RecordingBackend(
      cycling(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(RepositoryAccessApiSuite.BranchBody, StatusCode(201)),
      )
    )

    onBackend(backend): api =>
      api.attempt
        .createBranchProtection(Handle, Name, CreateBranchProtection.on(Rule))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on create must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("repos.branchProtections.edit PATCHes only what the caller stated"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.BranchBody))
    val command = EditBranchProtection.of(BranchProtectionSettings.Unchanged.applyingToAdmins(true))

    onBackend(backend): api =>
      api.editBranchProtection(Handle, Name, Rule, command).map: _ =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(pathOf(backend), s"$Root/branch_protections/main")
        assertEquals(bodyOf(backend), """{"apply_to_admins":true}""")

  test("an edit that states nothing sends an empty object rather than resetting the rule"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.BranchBody))

    onBackend(backend): api =>
      api
        .editBranchProtection(Handle, Name, Rule, EditBranchProtection.Nothing)
        .map(_ => assertEquals(bodyOf(backend), "{}"))

  test("repos.branchProtections.edit is never retried, because a PATCH is not safe"):
    val backend = RecordingBackend(
      cycling(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(RepositoryAccessApiSuite.BranchBody, StatusCode(200)),
      )
    )

    onBackend(backend): api =>
      api.attempt
        .editBranchProtection(Handle, Name, Rule, EditBranchProtection.Nothing)
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the PATCH was retried"))

  test("repos.branchProtections.delete is a DELETE that reads no body"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.deleteBranchProtection(Handle, Name, Rule).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/branch_protections/main")

  test("deleting a rule by name is not retried, because a name the instance reuses could name a different rule"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onBackend(backend): api =>
      api.attempt
        .deleteBranchProtection(Handle, Name, Rule)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on this delete must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "a delete by rule name was retried")

  // --- tag protections ------------------------------------------------------

  test("repos.tagProtections.list is a bare array and takes no paging parameters"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.TagListBody))

    onBackend(backend): api =>
      api.listTagProtections(Handle, Name).map: rules =>
        assertEquals(pathOf(backend), s"$Root/tag_protections")
        assertEquals(queryOf(backend), Nil)
        assertEquals(rules.map(_.namePattern), Vector("v*"))

  test("a single tag protection read addresses the rule by id"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.TagBody))

    onBackend(backend): api =>
      api.tagProtection(Handle, Name, TagRule).map: rule =>
        assertEquals(pathOf(backend), s"$Root/tag_protections/17")
        assertEquals(rule.id.value, 17L)

  test("repos.tagProtections.create POSTs all three properties, empty whitelists included"):
    val backend = RecordingBackend(responding(201, RepositoryAccessApiSuite.TagBody))
    val command = CreateTagProtection.matching(orFail(TagNamePattern.from("v*")))

    onBackend(backend): api =>
      api.createTagProtection(Handle, Name, command).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/tag_protections")
        assertEquals(bodyOf(backend), """{"name_pattern":"v*","whitelist_usernames":[],"whitelist_teams":[]}""")

  test("repos.tagProtections.edit PATCHes only what the caller stated"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.TagBody))
    val command = EditTagProtection.Nothing.exemptingTeams(Vector("release"))

    onBackend(backend): api =>
      api.editTagProtection(Handle, Name, TagRule, command).map: _ =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(pathOf(backend), s"$Root/tag_protections/17")
        assertEquals(bodyOf(backend), """{"whitelist_teams":["release"]}""")

  test("deleting a tag protection by id is retried, because the instance never reuses that number"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onBackend(backend): api =>
      api.deleteTagProtection(Handle, Name, TagRule).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/tag_protections/17")
        assertEquals(backend.allInteractions.size, 2, "a delete by id was not retried")

  // --- collaborators --------------------------------------------------------

  test("repos.collaborators.list pages the repository's collaborators"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.UserListBody))

    onBackend(backend): api =>
      api.listCollaborators(Handle, Name, window(2, 25)).map: page =>
        assertEquals(pathOf(backend), s"$Root/collaborators")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))
        assertEquals(page.items.map(_.login), Vector("alice"))

  test("the collaborator listing ends where rel=next says it ends, not where a short page suggests"):
    onStub(responding(200, RepositoryAccessApiSuite.UserListBody, RepositoryAccessApiSuite.PagedHeaders)): api =>
      api.listCollaborators(Handle, Name, window(1, 30)).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.totalCount, Some(97))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page past the end is an empty page, not a failure"):
    onStub(responding(200, "[]")): api =>
      api.listCollaborators(Handle, Name, PageParams.First).map: page =>
        assertEquals(page.items, Vector.empty[User])
        assertEquals(page.isLast, true)

  test("repos.collaborators.check is a GET with no body, and its 204 is the only yes there is"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.checkCollaborator(Handle, Name, Collaborator).map: _ =>
        assertEquals(methodOf(backend), "GET")
        assertEquals(pathOf(backend), s"$Root/collaborators/alice")

  test("a 404 from the collaborator check reaches the typed rail as the no it is"):
    onStub(responding(404, RepositoryAccessApiSuite.NotFoundBody)): api =>
      api.attempt.checkCollaborator(Handle, Name, Collaborator).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 404)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("repos.collaborators.add PUTs the level under the key the spec names"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.addCollaborator(Handle, Name, Collaborator, CollaboratorPermission.Write).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(pathOf(backend), s"$Root/collaborators/alice")
        assertEquals(bodyOf(backend), """{"permission":"write"}""")

  test("adding a collaborator is retried, because it states a level rather than asserting an absence"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onBackend(backend): api =>
      api
        .addCollaborator(Handle, Name, Collaborator, CollaboratorPermission.Admin)
        .map(_ => assertEquals(backend.allInteractions.size, 2, "the PUT was not retried"))

  test("repos.collaborators.delete addresses the account by name and is retried"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onBackend(backend): api =>
      api.deleteCollaborator(Handle, Name, Collaborator).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/collaborators/alice")
        assertEquals(backend.allInteractions.size, 2, "the revocation was not retried")

  test("repos.collaborators.permission reads the level both parsed and verbatim"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.PermissionBody))

    onBackend(backend): api =>
      api.collaboratorAccess(Handle, Name, Collaborator).map: access =>
        assertEquals(pathOf(backend), s"$Root/collaborators/alice/permission")
        assertEquals(access.permission, Some(TeamPermission.Write))
        assertEquals(access.rawPermission, Some("write"))
        assertEquals(access.roleName, Some("Collaborator"))
        assertEquals(access.user.login, "alice")

  // --- deploy keys ----------------------------------------------------------

  test("repos.keys.list sends its filters before the paging parameters"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.DeployKeyListBody))
    val query   = DeployKeyQuery.Empty.forKeyId(91L).withFingerprint("SHA256:abc")

    onBackend(backend): api =>
      api.listDeployKeys(Handle, Name, query, PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Root/keys")
        assertEquals(
          queryOf(backend),
          List("key_id" -> "91", "fingerprint" -> "SHA256:abc", "page" -> "1", "limit" -> "30"),
        )
        assertEquals(page.items.map(_.id.value), Vector(4L))

  test("a single deploy key read addresses the grant by id, not by the key it wraps"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.DeployKeyBody))

    onBackend(backend): api =>
      api.deployKey(Handle, Name, Key).map: deployKey =>
        assertEquals(pathOf(backend), s"$Root/keys/4")
        assertEquals(deployKey.id.value, 4L)
        assertEquals(deployKey.keyId, Some(91L))

  test("repos.keys.create POSTs all three properties, the grant stated explicitly"):
    val backend = RecordingBackend(responding(201, RepositoryAccessApiSuite.DeployKeyBody))
    val command = orFail(CreateDeployKey.of("ci runner", "ssh-ed25519 AAAA deploy@ci")).readOnly

    onBackend(backend): api =>
      api.createDeployKey(Handle, Name, command).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/keys")
        assertEquals(
          bodyOf(backend),
          """{"title":"ci runner","key":"ssh-ed25519 AAAA deploy@ci","read_only":true}""",
        )

  test("a deploy key's public material is not redacted, unlike an Actions secret"):
    onStub(responding(200, RepositoryAccessApiSuite.DeployKeyBody)): api =>
      api.deployKey(Handle, Name, Key).map: deployKey =>
        assertEquals(deployKey.key, "ssh-ed25519 AAAAC3Nz deploy@ci")
        assert(deployKey.toString.contains("ssh-ed25519 AAAAC3Nz"), "the public key was masked, which it need not be")

  test("deleting a deploy key by id is retried, because the instance never reuses that number"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onBackend(backend): api =>
      api.deleteDeployKey(Handle, Name, Key).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/keys/4")
        assertEquals(backend.allInteractions.size, 2, "a delete by id was not retried")

  // --- teams ----------------------------------------------------------------

  test("repos.teams.list is a bare array and takes no paging parameters"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.TeamListBody))

    onBackend(backend): api =>
      api.listTeams(Handle, Name).map: teams =>
        assertEquals(pathOf(backend), s"$Root/teams")
        assertEquals(queryOf(backend), Nil)
        assertEquals(teams.map(_.name), Vector("owners"))

  test("repos.teams.check answers with a whole team, unlike its collaborator counterpart"):
    val backend = RecordingBackend(responding(200, RepositoryAccessApiSuite.TeamBody))

    onBackend(backend): api =>
      api.checkTeam(Handle, Name, Squad).map: team =>
        assertEquals(pathOf(backend), s"$Root/teams/owners")
        assertEquals(team.id.value, 5L)
        assertEquals(team.permission, Some(TeamPermission.Admin))

  test("repos.teams.add is a PUT that carries no body at all"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.addTeam(Handle, Name, Squad).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(pathOf(backend), s"$Root/teams/owners")
        assertEquals(bodyOf(backend), "empty")

  test("granting a team is not retried, because a team name is not an id the instance never reuses"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onBackend(backend): api =>
      api.attempt
        .addTeam(Handle, Name, Squad)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a team grant must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the team grant was retried")

  test("repos.teams.delete withdraws the grant and is not retried either"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onBackend(backend): api =>
      api.attempt
        .deleteTeam(Handle, Name, Squad)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a team withdrawal must not be retried into a success, got $outcome")
          assertEquals(methodOf(backend), "DELETE")
          assertEquals(pathOf(backend), s"$Root/teams/owners")
          assertEquals(backend.allInteractions.size, 1, "the team withdrawal was retried")

  // --- failures -------------------------------------------------------------

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onStub(responding(404, RepositoryAccessApiSuite.NotFoundBody)): api =>
      api.branchProtection(Handle, Name, Rule).failed.map:
        case CodebergException(error) =>
          assertEquals(
            summary(error),
            (RepositoryAccessApi.GetBranchProtectionOperation, 404, Some("GetBranchProtection")),
          )
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onStub(responding(404, RepositoryAccessApiSuite.NotFoundBody)): api =>
      for
        raised <- api.branchProtection(Handle, Name, Rule).failed
        typed  <- api.attempt.branchProtection(Handle, Name, Rule)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a paged listing failure as well, so the choice of rail is only a choice of style"):
    onStub(responding(403, RepositoryAccessApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.listCollaborators(Handle, Name, PageParams.First).failed
        typed  <- api.attempt.listCollaborators(Handle, Name, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning write as well"):
    onStub(responding(403, RepositoryAccessApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.addCollaborator(Handle, Name, Collaborator, CollaboratorPermission.Read).failed
        typed  <- api.attempt.addCollaborator(Handle, Name, Collaborator, CollaboratorPermission.Read)
      yield assertRailsAgree(raised, typed)

  test("a 423 on a protection write is an Api failure like any other status"):
    onStub(responding(423, RepositoryAccessApiSuite.ArchivedBody)): api =>
      api.attempt.createBranchProtection(Handle, Name, CreateBranchProtection.on(Rule)).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 423)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a 405 on a team endpoint is an Api failure too — it is what a user-owned repository answers"):
    onStub(responding(405, RepositoryAccessApiSuite.NotAnOrgBody)): api =>
      api.attempt.listTeams(Handle, Name).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 405)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onStub(responding(200, """{"enable_push":true}""")): api =>
      api.attempt.branchProtection(Handle, Name, Rule).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.rule_name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a bad element of a listing reports its position"):
    onStub(responding(200, """[{"rule_name":"main"},{"enable_push":true}]""")): api =>
      api.attempt.listBranchProtections(Handle, Name).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].rule_name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a failure carries the operation id of the endpoint it came from, so an alert can name it"):
    onStub(responding(403, RepositoryAccessApiSuite.ForbiddenBody)): api =>
      api.attempt.deleteDeployKey(Handle, Name, Key).map: outcome =>
        assertEquals(operation(outcome), RepositoryAccessApi.DeleteDeployKeyOperation)

  // --- assertions -----------------------------------------------------------

  private def assertRailsAgree[A](raised: Throwable, typed: Either[CodebergError, A]): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  private def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body) => (ctx.operation, status, body.message)
      case other                                => fail(s"expected an Api failure, got ${other.describe}")

  private def operation[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.Api(ctx, _, _)) => ctx.operation
      case other                              => fail(s"expected an Api failure, got $other")

  // --- harness --------------------------------------------------------------

  private def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  private def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** A backend that answers `first` once and `rest` from then on — how a retry is made observable. */
  private def cycling(first: Response[StubBody], rest: Response[StubBody]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(first, rest)

  private def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  /** The dialled URI without its query string, written with `indexOf` because universal equality is banned. */
  private def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?')

    if query < 0 then uri else uri.take(query)

  private def queryOf(backend: RecordingBackend): List[(String, String)] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.params.toSeq.toList
      case None               => fail("no request reached the backend")

  private def methodOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.method.method
      case None               => fail("no request reached the backend")

  private def bodyOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.body.show.stripPrefix("string: ")
      case None               => fail("no request reached the backend")

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def onStub[A](backend: Backend[Future])(use: RepositoryAccessApi => Future[A]): Future[A] =
    onBackend(backend)(use)

  /** Builds the pipeline this group's API sits on, and releases the timer whatever the outcome. */
  private def onBackend[A](backend: Backend[Future])(use: RepositoryAccessApi => Future[A]): Future[A] =
    given Exec[Future] = FutureExec()

    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = RepositoryAccessApiSuite.PromptRetry)
    val timer  = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(RepositoryAccessApi(pipeline)).transform: outcome =>
      timer.close()
      outcome

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are hand-written from `spec/swagger.v1.json`; no endpoint in this group has a golden capture.
  */
object RepositoryAccessApiSuite:

  private val BranchBody: String =
    """{"rule_name": "main", "require_signed_commits": true, "apply_to_admins": true, "required_approvals": 2}"""

  private val BranchListBody: String = s"[$BranchBody]"

  private val TagBody: String =
    """{"id": 17, "name_pattern": "v*", "whitelist_teams": ["release"]}"""

  private val TagListBody: String = s"[$TagBody]"

  private val UserBody: String = """{"id": 1, "login": "alice"}"""

  private val UserListBody: String = s"[$UserBody]"

  private val PermissionBody: String =
    s"""{"permission": "write", "role_name": "Collaborator", "user": $UserBody}"""

  private val DeployKeyBody: String =
    """{"id": 4, "key_id": 91, "key": "ssh-ed25519 AAAAC3Nz deploy@ci", "title": "ci runner", "read_only": true}"""

  private val DeployKeyListBody: String = s"[$DeployKeyBody]"

  private val TeamBody: String =
    """{"id": 5, "name": "owners", "permission": "admin", "units": ["repo.code"]}"""

  private val TeamListBody: String = s"[$TeamBody]"

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host and path swapped for this endpoint's. */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "97"),
      Header(
        "Link",
        "<https://forge.example/api/v1/repos/forgejo/forgejo/collaborators?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/repos/forgejo/forgejo/collaborators?limit=30&page=4>; rel=\"last\"",
      ),
    )

  private val NotFoundBody: String =
    """{"message":"GetBranchProtection","url":"https://codeberg.org/api/swagger","errors":["rule does not exist"]}"""

  private val ForbiddenBody: String =
    """{"message":"token does not have at least one of required scope(s): [write:repository]"}"""

  private val ArchivedBody: String =
    """{"message":"CreateBranchProtection","errors":["repository is archived"]}"""

  private val NotAnOrgBody: String =
    """{"message":"ListTeams","errors":["repository is not owned by an organization"]}"""

  /** Retries promptly and predictably: the default policy would make the retry tests take a quarter of a second. */
  private val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
