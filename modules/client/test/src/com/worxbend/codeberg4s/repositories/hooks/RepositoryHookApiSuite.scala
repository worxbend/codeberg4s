package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

/** [[RepositoryHookApi]] over a `BackendStub`: which URI is dialled, which body is sent, and which writes are repeated.
  *
  * The retry assertions are the point of this suite. Three of the write endpoints here are retried and two are not, and
  * the difference is a promise the Scaladoc makes on each of them — a promise a comment cannot keep.
  */
final class RepositoryHookApiSuite extends FunSuite with HookStubs:

  private val Identifier: HookId = orFail(HookId.from(4242L))

  private val PreReceive: GitHookName = orFail(GitHookName.from("pre-receive"))

  // --- webhook reads --------------------------------------------------------

  test("a hook listing targets the repository's hooks and pages it"):
    val backend = RecordingBackend(responding(200, RepositoryHookApiSuite.HookListBody))

    onApi(backend): api =>
      api.list(Handle, Name, window(2, 25)).map: page =>
        assertEquals(pathOf(backend), s"$Repository/hooks")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))
        assertEquals(page.items.map(_.id.value), Vector(4242L))

  test("a hook listing ends where rel=next says it ends, not where a short page suggests"):
    val headers = pagedHeaders(97, s"$Repository/hooks?limit=30&page=2")

    onApi(responding(200, RepositoryHookApiSuite.HookListBody, headers)): api =>
      api.list(Handle, Name, window(1, 30)).map: page =>
        assertEquals(page.totalCount, Some(97))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page past the end is an empty page, not a failure"):
    onApi(responding(200, "[]")): api =>
      api.list(Handle, Name, window(9, 30)).map: page =>
        assertEquals(page.items, Vector.empty[Webhook])
        assertEquals(page.isLast, true)

  test("a single-hook read addresses a hook by its instance-wide id"):
    val backend = RecordingBackend(responding(200, RepositoryHookApiSuite.HookBody))

    onApi(backend): api =>
      api.get(Handle, Name, Identifier).map: hook =>
        assertEquals(pathOf(backend), s"$Repository/hooks/4242")
        assertEquals(methodOf(backend), "GET")
        assertEquals(hook.configuration.url, Some("https://ci.example/forgejo"))

  test("no secret from a response can reach the caller, whatever the instance sends"):
    onApi(responding(200, RepositoryHookApiSuite.LeakyHookBody)): api =>
      api.get(Handle, Name, Identifier).map: hook =>
        assert(!hook.toString.contains("hunter2"), s"a secret reached the caller: $hook")
        assert(!hook.toString.contains("abc123"), s"an authorization header reached the caller: $hook")

  // --- webhook writes -------------------------------------------------------

  test("hooks.create POSTs the rendered CreateHookOption to the repository's hooks"):
    val backend = RecordingBackend(responding(201, RepositoryHookApiSuite.HookBody))
    val command = CreateHook
      .to(HookType.Forgejo, "https://ci.example/forgejo", HookContentType.Json)
      .subscribingTo(HookEvent.Push)
      .activated

    onApi(backend): api =>
      api.create(Handle, Name, command).map: hook =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Repository/hooks")
        assertEquals(
          bodyOf(backend),
          """{"type":"forgejo","config":{"content_type":"json","url":"https://ci.example/forgejo"},""" +
            """"events":["push"],"active":true}""",
        )
        assertEquals(hook.id.value, 4242L)

  test("hooks.create is never retried, because a repeat would create a second hook"):
    val backend = RecordingBackend(flakyThen(201, RepositoryHookApiSuite.HookBody))
    val command = CreateHook.to(HookType.Forgejo, "https://ci.example/forgejo", HookContentType.Json)

    onApi(backend): api =>
      api.attempt.create(Handle, Name, command).map: outcome =>
        assert(outcome.isLeft, s"a 503 on create must not be retried into a success, got $outcome")
        assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("hooks.edit PATCHes only what the command sets"):
    val backend = RecordingBackend(responding(200, RepositoryHookApiSuite.HookBody))

    onApi(backend): api =>
      api
        .edit(Handle, Name, Identifier, EditHook.Empty.deactivated)
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), s"$Repository/hooks/4242")
          assertEquals(bodyOf(backend), """{"active":false}""")

  test("hooks.edit is retried, because it names one hook and states the value it should have"):
    val backend = RecordingBackend(flakyThen(200, RepositoryHookApiSuite.HookBody))

    onApi(backend): api =>
      api.edit(Handle, Name, Identifier, EditHook.Empty.deactivated).map: hook =>
        assertEquals(hook.id.value, 4242L)
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("hooks.delete addresses the hook by id and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.delete(Handle, Name, Identifier).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Repository/hooks/4242")
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("hooks.test POSTs to the hook's tests route and carries the ref the caller named"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api
        .test(Handle, Name, Identifier, Some("refs/heads/main"))
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), s"$Repository/hooks/4242/tests")
          assertEquals(queryOf(backend), List("ref" -> "refs/heads/main"))

  test("hooks.test sends no ref when the caller named none"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.test(Handle, Name, Identifier, None).map(_ => assertEquals(queryOf(backend), Nil))

  test("hooks.test is never retried, because a repeat delivers a second payload to a third party"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.attempt
        .test(Handle, Name, Identifier, None)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a hook test must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the test delivery was repeated")

  // --- Git hooks ------------------------------------------------------------

  test("the Git hook listing targets hooks/git and is not paged"):
    val backend = RecordingBackend(responding(200, RepositoryHookApiSuite.GitHookListBody))

    onApi(backend): api =>
      api.gitHooks(Handle, Name).map: hooks =>
        assertEquals(pathOf(backend), s"$Repository/hooks/git")
        assertEquals(queryOf(backend), Nil)
        assertEquals(hooks.map(_.name.value), Vector("pre-receive"))

  test("a single Git hook read addresses it by the name Git gives it"):
    val backend = RecordingBackend(responding(200, RepositoryHookApiSuite.GitHookBody))

    onApi(backend): api =>
      api.gitHook(Handle, Name, PreReceive).map: hook =>
        assertEquals(pathOf(backend), s"$Repository/hooks/git/pre-receive")
        assertEquals(hook.content, Some("#!/bin/sh\nexit 0\n"))

  test("a Git hook edit PATCHes the script and is retried, because it assigns a stated slot"):
    val backend = RecordingBackend(flakyThen(200, RepositoryHookApiSuite.GitHookBody))

    onApi(backend): api =>
      api
        .editGitHook(Handle, Name, PreReceive, EditGitHook.of("#!/bin/sh\nexit 0\n"))
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), s"$Repository/hooks/git/pre-receive")
          assertEquals(bodyOf(backend), """{"content":"#!/bin/sh\nexit 0\n"}""")
          assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("a Git hook delete clears the slot and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.deleteGitHook(Handle, Name, PreReceive).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Repository/hooks/git/pre-receive")
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  // --- failures -------------------------------------------------------------

  test("a 404 reaches both rails as the very same failure"):
    onApi(responding(404, HookStubs.NotFoundBody)): api =>
      for
        raised <- api.get(Handle, Name, Identifier).failed
        typed  <- api.attempt.get(Handle, Name, Identifier)
      yield
        assertRailsAgree(raised, typed)
        assertEquals(summary(materialise(typed))._1, RepositoryHookApi.GetOperation)

  test("a 403 on the Git hook listing reaches both rails identically, as an administrative refusal does"):
    onApi(responding(403, HookStubs.NotFoundBody)): api =>
      for
        raised <- api.gitHooks(Handle, Name).failed
        typed  <- api.attempt.gitHooks(Handle, Name)
      yield assertRailsAgree(raised, typed)

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """{"type":"forgejo"}""")): api =>
      api.attempt.get(Handle, Name, Identifier).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a bad element of a hook listing reports its position, all the way through the pipeline"):
    onApi(responding(200, """[{"id":1},{"type":"forgejo"}]""")): api =>
      api.attempt.list(Handle, Name, window(1, 30)).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def materialise[A](result: Either[CodebergError, A]): CodebergError =
    result match
      case Left(error) => error
      case Right(_)    => fail("expected a failure")

  private def onApi[A](backend: Backend[Future])(use: RepositoryHookApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(RepositoryHookApi(pipeline)))

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object RepositoryHookApiSuite:

  private val HookBody: String =
    """{
      |  "id": 4242,
      |  "type": "forgejo",
      |  "config": {"url": "https://ci.example/forgejo", "content_type": "json"},
      |  "events": ["push"],
      |  "active": true,
      |  "created_at": "2026-07-30T21:14:15+02:00"
      |}""".stripMargin

  private val HookListBody: String = s"[$HookBody]"

  /** A hook payload from an instance that echoes both credentials back, which this library must survive. */
  private val LeakyHookBody: String =
    """{
      |  "id": 4242,
      |  "authorization_header": "token abc123",
      |  "config": {"url": "https://ci.example/forgejo", "secret": "hunter2"}
      |}""".stripMargin

  private val GitHookBody: String =
    """{"name": "pre-receive", "is_active": true, "content": "#!/bin/sh\nexit 0\n"}"""

  private val GitHookListBody: String = s"[$GitHookBody]"
