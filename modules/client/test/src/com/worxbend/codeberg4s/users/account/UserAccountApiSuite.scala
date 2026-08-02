package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.RepoName

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend
import sttp.model.Header

import scala.concurrent.Future

import java.nio.charset.StandardCharsets
import java.util.Base64

/** [[UserAccountApi]] over a `BackendStub`; see [[AccountApiSuite]] for the harness and for the evidence note.
  *
  * Three things in this suite are specific to this class and to nothing else in the library: the avatar `POST` sends a
  * base64 string in a JSON body rather than a multipart upload, the email `DELETE` carries a body, and adding an
  * address is not retried while removing one is. Each has a test that says so.
  */
final class UserAccountApiSuite extends AccountApiSuite:

  private val Address: EmailAddress = orFail(EmailAddress.from("maintainer@example.org"))

  private val Second: EmailAddress = orFail(EmailAddress.from("second@example.org"))

  private def image: AvatarImage =
    AvatarImage.ofBytes("PNGDATA".getBytes(StandardCharsets.UTF_8))

  private def blob: String =
    Base64.getEncoder.encodeToString("PNGDATA".getBytes(StandardCharsets.UTF_8))

  // --- settings -------------------------------------------------------------

  test("the settings read targets /user/settings with no query"):
    val backend = RecordingBackend(responding(200, UserAccountApiSuite.SettingsBody))

    onApi(backend): api =>
      api.settings().map: settings =>
        assertEquals(pathOf(backend), s"$Root/settings")
        assertEquals(queryOf(backend), Nil)
        assertEquals(settings.fullName, Some("A Maintainer"))
        assertEquals(settings.hidesEmail, true)

  test("the settings update PATCHes only what the command mentions and reads the settings back"):
    val backend = RecordingBackend(responding(200, UserAccountApiSuite.SettingsBody))

    onApi(backend): api =>
      api.updateSettings(UpdateUserSettings.Empty.hidingActivity).map: settings =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(pathOf(backend), s"$Root/settings")
        assertEquals(bodyOf(backend), """{"hide_activity":true}""")
        assertEquals(settings.hidesActivity, false)

  test("the settings update is retried, because it names one identity and states what it wants"):
    val backend = RecordingBackend(failingThenSucceeding(200, UserAccountApiSuite.SettingsBody))

    onApi(backend): api =>
      api.updateSettings(UpdateUserSettings.Empty.hidingActivity).map(_ => assertEquals(attemptsOn(backend), 2))

  // --- avatar ---------------------------------------------------------------

  test("the avatar update POSTs a base64 string in a JSON body, not a multipart upload"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.updateAvatar(image).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/avatar")
        assertEquals(bodyOf(backend), s"""{"image":"$blob"}""")

  test("the avatar update is never retried, because it is a POST"):
    val backend = RecordingBackend(failingThenSucceeding(204, ""))

    onApi(backend): api =>
      api.attempt.updateAvatar(image).map(_ => assertEquals(attemptsOn(backend), 1, "the POST was retried"))

  test("the avatar delete is a DELETE with no body"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.deleteAvatar().map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/avatar")

  test("the avatar delete is retried, because it asks for a state rather than for an object"):
    val backend = RecordingBackend(failingThenSucceeding(204, ""))

    onApi(backend): api =>
      api.deleteAvatar().map(_ => assertEquals(attemptsOn(backend), 2))

  // --- emails ---------------------------------------------------------------

  test("the email listing is not paged, because the spec declares no window for it"):
    val backend = RecordingBackend(responding(200, UserAccountApiSuite.EmailListBody))

    onApi(backend): api =>
      api.emails().map: addresses =>
        assertEquals(pathOf(backend), s"$Root/emails")
        assertEquals(queryOf(backend), Nil)
        assertEquals(addresses.map(_.address.value), Vector("maintainer@example.org"))
        assertEquals(addresses.map(_.isPrimary), Vector(true))

  test("adding addresses POSTs them all in one request and reads the account's addresses back"):
    val backend = RecordingBackend(responding(201, UserAccountApiSuite.EmailListBody))

    onApi(backend): api =>
      api.addEmails(Address, Second).map: addresses =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/emails")
        assertEquals(bodyOf(backend), """{"emails":["maintainer@example.org","second@example.org"]}""")
        assertEquals(addresses.length, 1)

  test("adding an address is never retried, because a repeat turns a success into a 422"):
    val backend = RecordingBackend(failingThenSucceeding(201, UserAccountApiSuite.EmailListBody))

    onApi(backend): api =>
      api.attempt.addEmails(Address).map(_ => assertEquals(attemptsOn(backend), 1, "the POST was retried"))

  test("removing addresses is a DELETE that carries a body, which is the only one in the library"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.deleteEmails(Address).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/emails")
        assertEquals(bodyOf(backend), """{"emails":["maintainer@example.org"]}""")

  test("removing an address is retried, because it is idempotent by address"):
    val backend = RecordingBackend(failingThenSucceeding(204, ""))

    onApi(backend): api =>
      api.deleteEmails(Address).map(_ => assertEquals(attemptsOn(backend), 2))

  // --- repositories ---------------------------------------------------------

  test("the repository listing pages and sends no order_by when the caller stated no preference"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.repositories(RepositoryOrder.Default, window(2, 20)).map: _ =>
        assertEquals(pathOf(backend), s"$Root/repos")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "20"))

  test("a stated ordering is sent after the paging parameters"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .repositories(RepositoryOrder.MostStars, PageParams.First)
        .map(_ => assertEquals(queryOf(backend).lastOption, Some("order_by" -> "moststars")))

  test("the repository listing ends where rel=next says it ends, not where a short page suggests"):
    onApi(responding(200, UserAccountApiSuite.RepoListBody, UserAccountApiSuite.PagedHeaders)): api =>
      api.repositories(RepositoryOrder.Default, window(1, 30)).map: page =>
        assertEquals(page.items.length, 1)
        assertEquals(page.totalCount, Some(97))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("creating a repository POSTs the name and the three flags to the account's own repository path"):
    val backend = RecordingBackend(responding(201, UserAccountApiSuite.RepoBody))

    onApi(backend): api =>
      api.createRepository(CreateRepository.named(orFail(RepoName.from("codeberg4s")))).map: repository =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/repos")
        assertEquals(bodyOf(backend), """{"name":"codeberg4s","private":false,"template":false,"auto_init":false}""")
        assertEquals(repository.slug.name.value, "codeberg4s")

  test("creating a repository is never retried, because a repeat cannot be told apart from a name clash"):
    val backend = RecordingBackend(failingThenSucceeding(201, UserAccountApiSuite.RepoBody))

    onApi(backend): api =>
      api.attempt
        .createRepository(CreateRepository.named(orFail(RepoName.from("codeberg4s"))))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the POST was retried"))

  // --- teams ----------------------------------------------------------------

  test("the team listing pages and reads the shared team model, organisation included"):
    val backend = RecordingBackend(responding(200, UserAccountApiSuite.TeamListBody))

    onApi(backend): api =>
      api.teams(PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Root/teams")
        assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))
        assertEquals(page.items.map(_.name), Vector("maintainers"))
        assertEquals(page.items.flatMap(_.organization).map(_.name.value), Vector("forgejo"))

  // --- failures -------------------------------------------------------------

  test("a 401 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(401, AccountApiSuite.UnauthorizedBody)): api =>
      api.settings().failed.map: failure =>
        assertEquals(
          summary(unwrap(failure)),
          (UserAccountApi.SettingsOperation, 401, Some("token is required")),
        )

  test("a 401 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(401, AccountApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.settings().failed
        typed  <- api.attempt.settings()
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning write as well"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.deleteAvatar().failed
        typed  <- api.attempt.deleteAvatar()
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a varargs write as well, so the choice of rail is only a choice of style"):
    onApi(responding(422, AccountApiSuite.NotFoundBody)): api =>
      for
        raised <- api.addEmails(Address).failed
        typed  <- api.attempt.addEmails(Address)
      yield assertRailsAgree(raised, typed)

  test("an address the instance sends that is not address-shaped fails the listing at its own position"):
    onApi(responding(200, """[{"email":"a@b.example"},{"email":"not-an-address"}]""")): api =>
      api.attempt.emails().map(outcome => assertEquals(decodingPathOf(outcome), "$[1].email"))

  test("a failure carries the operation id of the endpoint it came from, so an alert can name it"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      api.attempt
        .teams(PageParams.First)
        .map(outcome => assertEquals(operationOf(outcome), UserAccountApi.TeamsOperation))

  private def unwrap(failure: Throwable): CodebergError =
    failure match
      case CodebergException(error) => error
      case other                    => fail(s"expected a CodebergException, got $other")

  private def onApi[A](backend: Backend[Future])(use: UserAccountApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(UserAccountApi(pipeline)))

/** The response bodies this suite stubs.
  *
  * The settings, email and team payloads are hand-written from `spec/swagger.v1.json`; the repository payload is
  * trimmed from `golden/repository/repo-single.json`, which is a captured response.
  */
object UserAccountApiSuite:

  private val SettingsBody: String =
    """{"full_name": "A Maintainer", "website": "", "location": "Somewhere", "hide_email": true,
      | "hide_activity": false, "enable_repo_unit_hints": true}""".stripMargin

  private val EmailListBody: String =
    """[{"email": "maintainer@example.org", "primary": true, "verified": true, "user_id": 31}]"""

  private val RepoBody: String =
    """{"id": 4711, "name": "codeberg4s", "full_name": "maintainer/codeberg4s",
      | "owner": {"id": 31, "login": "maintainer"}, "private": false, "empty": true}""".stripMargin

  private val RepoListBody: String = s"[$RepoBody]"

  private val TeamListBody: String =
    """[{"id": 9, "name": "maintainers", "permission": "write", "units": ["repo.code"],
      | "organization": {"id": 3, "name": "forgejo", "username": "forgejo"}}]""".stripMargin

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host and path swapped for this endpoint's. */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "97"),
      Header(
        "Link",
        "<https://forge.example/api/v1/user/repos?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/user/repos?limit=30&page=4>; rel=\"last\"",
      ),
    )
