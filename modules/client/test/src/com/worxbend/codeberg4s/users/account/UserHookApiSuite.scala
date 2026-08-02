package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.hooks.CreateHook
import com.worxbend.codeberg4s.repositories.hooks.EditHook
import com.worxbend.codeberg4s.repositories.hooks.HookConfig
import com.worxbend.codeberg4s.repositories.hooks.HookContentType
import com.worxbend.codeberg4s.repositories.hooks.HookEvent
import com.worxbend.codeberg4s.repositories.hooks.HookId
import com.worxbend.codeberg4s.repositories.hooks.HookSecret
import com.worxbend.codeberg4s.repositories.hooks.HookType
import com.worxbend.codeberg4s.repositories.hooks.Webhook

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import scala.concurrent.Future

/** [[UserHookApi]] over a `BackendStub`; see [[AccountApiSuite]] for the harness and for the evidence note.
  *
  * The subject is that the account hook surface reaches `/user/hooks` while reusing the repository hook models outright
  * — including their credential discipline, which is checked here rather than assumed: a secret goes out in the request
  * body and never comes back in a decoded hook.
  */
final class UserHookApiSuite extends AccountApiSuite:

  private val Hook: HookId = orFail(HookId.from(11L))

  private val HooksRoot: String = s"$Root/hooks"

  private def command: CreateHook =
    CreateHook
      .to(HookType.Forgejo, "https://ci.example/hook", HookContentType.Json)
      .subscribingTo(HookEvent.Push, HookEvent.Release)
      .signedWith(orFail(HookSecret.from("hunter2")))
      .activated

  test("the listing targets the account's hooks and pages"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.list(window(3, 10)).map: _ =>
        assertEquals(pathOf(backend), HooksRoot)
        assertEquals(queryOf(backend), List("page" -> "3", "limit" -> "10"))

  test("a listing with no paging headers reports an unknown total and no next page, never zero"):
    onApi(responding(200, UserHookApiSuite.ListBody)): api =>
      api.list(PageParams.First).map: page =>
        assertEquals(page.items.length, 1)
        assertEquals(page.totalCount, None)
        assertEquals(page.nextPage, None)

  test("a page past the end is an empty page, not a failure"):
    onApi(responding(200, "[]")): api =>
      api.list(PageParams.First).map(page => assertEquals(page.items, Vector.empty[Webhook]))

  test("a single-hook read addresses the hook by id and reads the shared hook model"):
    val backend = RecordingBackend(responding(200, UserHookApiSuite.HookBody))

    onApi(backend): api =>
      api.get(Hook).map: hook =>
        assertEquals(pathOf(backend), s"$HooksRoot/11")
        assertEquals(hook.id.value, 11L)
        assertEquals(hook.hookType, Some(HookType.Forgejo))
        assertEquals(hook.subscribesTo(HookEvent.Push), true)

  test("creating a hook POSTs the shared create body, secret merged into the config"):
    val backend = RecordingBackend(responding(201, UserHookApiSuite.HookBody))

    onApi(backend): api =>
      api.create(command).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), HooksRoot)
        assertEquals(
          bodyOf(backend),
          """{"type":"forgejo","config":{"content_type":"json","url":"https://ci.example/hook",""" +
            """"secret":"hunter2"},"events":["push","release"],"active":true}""",
        )

  test("creating a hook is never retried, because a repeat delivers every event twice"):
    val backend = RecordingBackend(failingThenSucceeding(201, UserHookApiSuite.HookBody))

    onApi(backend): api =>
      api.attempt.create(command).map(_ => assertEquals(attemptsOn(backend), 1, "the POST was retried"))

  test("a secret sent on the way in never comes back on the way out"):
    onApi(responding(201, UserHookApiSuite.HookWithSecretBody)): api =>
      api.create(command).map: hook =>
        assertEquals(hook.configuration.valueOf(HookConfig.SecretKey), None)
        assertEquals(hook.configuration.valueOf(HookConfig.AuthorizationHeaderKey), None)
        assert(!hook.toString.contains("hunter2"), s"the decoded hook leaked a credential: $hook")

  test("editing a hook PATCHes only what the command mentions"):
    val backend = RecordingBackend(responding(200, UserHookApiSuite.HookBody))

    onApi(backend): api =>
      api.edit(Hook, EditHook.Empty.deactivated).map: _ =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(pathOf(backend), s"$HooksRoot/11")
        assertEquals(bodyOf(backend), """{"active":false}""")

  test("editing a hook is retried, because it names a row id and states the value it wants"):
    val backend = RecordingBackend(failingThenSucceeding(200, UserHookApiSuite.HookBody))

    onApi(backend): api =>
      api.edit(Hook, EditHook.Empty.deactivated).map(_ => assertEquals(attemptsOn(backend), 2))

  test("deleting a hook is a DELETE by id that reads no body"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.delete(Hook).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$HooksRoot/11")

  test("deleting a hook is retried, because a row id is never reused"):
    val backend = RecordingBackend(failingThenSucceeding(204, ""))

    onApi(backend): api =>
      api.delete(Hook).map(_ => assertEquals(attemptsOn(backend), 2))

  // --- failures -------------------------------------------------------------

  test("a 401 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(401, AccountApiSuite.UnauthorizedBody)): api =>
      api.get(Hook).failed.map: failure =>
        assertEquals(
          summary(unwrap(failure)),
          (UserHookApi.GetOperation, 401, Some("token is required")),
        )

  test("a 401 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(401, AccountApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.get(Hook).failed
        typed  <- api.attempt.get(Hook)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning delete as well"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.delete(Hook).failed
        typed  <- api.attempt.delete(Hook)
      yield assertRailsAgree(raised, typed)

  test("a 200 whose payload has no id becomes DecodingFailed at the id's own path"):
    onApi(responding(200, """{"type":"forgejo"}""")): api =>
      api.attempt.get(Hook).map(outcome => assertEquals(decodingPathOf(outcome), "$.id"))

  test("a bad element of a listing reports its own position"):
    onApi(responding(200, """[{"id":1},{"type":"forgejo"}]""")): api =>
      api.attempt.list(PageParams.First).map(outcome => assertEquals(decodingPathOf(outcome), "$[1].id"))

  private def unwrap(failure: Throwable): CodebergError =
    failure match
      case CodebergException(error) => error
      case other                    => fail(s"expected a CodebergException, got $other")

  private def onApi[A](backend: Backend[Future])(use: UserHookApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(UserHookApi(pipeline)))

/** The response bodies this suite stubs, all hand-written from `spec/swagger.v1.json`. */
object UserHookApiSuite:

  private val HookBody: String =
    """{"id": 11, "type": "forgejo", "active": true, "events": ["push", "release"],
      | "config": {"url": "https://ci.example/hook", "content_type": "json"},
      | "created_at": "2026-07-30T19:14:15+02:00", "updated_at": "2026-07-30T19:14:15+02:00"}""".stripMargin

  /** A hook whose instance echoed the credentials back, which [[HookConfig]] refuses to hold. */
  private val HookWithSecretBody: String =
    """{"id": 11, "type": "forgejo", "authorization_header": "Bearer hunter2",
      | "config": {"url": "https://ci.example/hook", "content_type": "json", "secret": "hunter2"}}""".stripMargin

  private val ListBody: String = s"[$HookBody]"
