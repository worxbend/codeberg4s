package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.hooks.CreateHook
import com.worxbend.codeberg4s.repositories.hooks.EditHook
import com.worxbend.codeberg4s.repositories.hooks.HookContentType
import com.worxbend.codeberg4s.repositories.hooks.HookEvent
import com.worxbend.codeberg4s.repositories.hooks.HookId
import com.worxbend.codeberg4s.repositories.hooks.HookSecret
import com.worxbend.codeberg4s.repositories.hooks.HookType

import sttp.client4.testing.RecordingBackend

import munit.FunSuite

/** [[OrganizationHookApi]] over a `BackendStub`.
  *
  * The subject is that an organisation hook is the '''repository''' hook model served from a second path: the same
  * `Webhook`, the same `CreateHook`, the same rendering, and the same write-only treatment of a secret. What differs is
  * the URI and the operation ids, so that is what is asserted.
  */
final class OrganizationHookApiSuite extends FunSuite with OrganizationStubs:

  private val Hook: HookId = orFail(HookId.from(7L))

  private val Secret: HookSecret = orFail(HookSecret.from("hunter2"))

  // --- request shape --------------------------------------------------------

  test("orgs.hooks.list pages the organisation's hooks, despite the response type's name"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.hooks
        .list(Org, window(2, 5))
        .map: _ =>
          assertEquals(methodOf(backend), "GET")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/hooks")
          assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "5"))

  test("the `orgs.hooks.get` route addresses the hook by its id, below the organisation"):
    val backend = RecordingBackend(responding(200, OrganizationHookApiSuite.HookBody))

    onApi(backend): api =>
      api.hooks
        .get(Org, Hook)
        .map: hook =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/hooks/7")
          assertEquals(hook.id.value, 7L)

  test("orgs.hooks.create posts CreateHookOption and merges the secret into config at the last moment"):
    val backend = RecordingBackend(responding(201, OrganizationHookApiSuite.HookBody))

    onApi(backend): api =>
      val command = CreateHook
        .to(HookType.Forgejo, "https://ci.example/forgejo", HookContentType.Json)
        .subscribingTo(HookEvent.Push, HookEvent.Repository)
        .signedWith(Secret)
        .activated

      api.hooks
        .create(Org, command)
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/hooks")
          assertEquals(
            bodyOf(backend),
            """{"type":"forgejo","config":{"content_type":"json","url":"https://ci.example/forgejo",""" +
              """"secret":"hunter2"},"events":["push","repository"],"active":true}""",
          )

  test("orgs.hooks.edit patches the hook and sends only what the command set"):
    val backend = RecordingBackend(responding(200, OrganizationHookApiSuite.HookBody))

    onApi(backend): api =>
      api.hooks
        .edit(Org, Hook, EditHook.Empty.deactivated)
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/hooks/7")
          assertEquals(bodyOf(backend), """{"active":false}""")

  test("orgs.hooks.delete sends a bodiless DELETE at the hook's own path"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.hooks
        .delete(Org, Hook)
        .map: _ =>
          assertEquals(methodOf(backend), "DELETE")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/hooks/7")
          assertEquals(bodyOf(backend), NoBody)

  // --- retries --------------------------------------------------------------

  test("orgs.hooks.create is never retried, because a repeat would create a second hook"):
    val backend = RecordingBackend(flakyThen(201, OrganizationHookApiSuite.HookBody))

    onApi(backend): api =>
      api.hooks.attempt
        .create(Org, CreateHook.to(HookType.Forgejo, "https://ci.example/forgejo", HookContentType.Json))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the hook create was repeated"))

  test("orgs.hooks.edit is retried, because a hook id is a row id the instance never reuses"):
    val backend = RecordingBackend(flakyThen(200, OrganizationHookApiSuite.HookBody))

    onApi(backend): api =>
      api.hooks
        .edit(Org, Hook, EditHook.Empty.activated)
        .map(_ => assertEquals(attemptsOn(backend), 2, "the 503 was not retried"))

  test("orgs.hooks.delete is retried, on the same argument as the edit"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.hooks.delete(Org, Hook).map(_ => assertEquals(attemptsOn(backend), 2, "the 503 was not retried"))

  // --- secrets --------------------------------------------------------------

  test("a secret an instance echoes back is dropped before a Webhook exists"):
    onApi(responding(200, OrganizationHookApiSuite.LeakyHookBody)): api =>
      api.hooks
        .get(Org, Hook)
        .map: hook =>
          assertEquals(hook.configuration.valueOf("secret"), None)
          assertEquals(hook.configuration.valueOf("authorization_header"), None)
          assertEquals(hook.configuration.url, Some("https://ci.example/forgejo"))

  test("a hook secret never renders itself, so nothing holding one can print it"):
    assertEquals(Secret.toString, "***")

  // --- payloads and failures ------------------------------------------------

  test("a hook listing decodes its elements and keeps unknown events rather than dropping them"):
    onApi(responding(200, s"[${OrganizationHookApiSuite.HookBody}]")): api =>
      api.hooks
        .list(Org, PageParams.First)
        .map: page =>
          assertEquals(page.items.map(_.id.value), Vector(7L))
          assertEquals(page.items.flatMap(_.events), Vector(HookEvent.Push, HookEvent.Other("forgejo_future_event")))

  test("a page past the end is an empty page, not a failure"):
    onApi(responding(200, "[]")): api =>
      api.hooks
        .list(Org, PageParams.First)
        .map: page =>
          assertEquals(page.items.size, 0)
          assertEquals(page.isLast, true)

  test("a 404 on the single-hook read reaches both rails identically"):
    onApi(responding(404, OrganizationStubs.NotFoundBody)): api =>
      for
        raised <- api.hooks.get(Org, Hook).failed
        typed  <- api.hooks.attempt.get(Org, Hook)
      yield
        assertEquals(operationOf(typed), OrganizationHookApi.GetOperation)
        assertRailsAgree(raised, typed)

  test("a 401 on the hook listing reaches both rails identically"):
    onApi(responding(401, OrganizationStubs.UnauthorizedBody)): api =>
      for
        raised <- api.hooks.list(Org, PageParams.First).failed
        typed  <- api.hooks.attempt.list(Org, PageParams.First)
      yield
        assertEquals(operationOf(typed), OrganizationHookApi.ListOperation)
        assertRailsAgree(raised, typed)

  test("a 2xx payload with no id becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """{"type":"forgejo"}""")): api =>
      api.hooks.attempt.get(Org, Hook).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.id")
        case other                                             => fail(s"expected a decoding failure, got $other")

/** The response bodies this suite stubs. Derived from the spec's `Hook` definition; no capture of a hook exists. */
object OrganizationHookApiSuite:

  /** A hook as the pinned spec defines one, including an event name this library does not recognise. */
  val HookBody: String =
    """{
      |  "id": 7,
      |  "type": "forgejo",
      |  "active": true,
      |  "config": {"url": "https://ci.example/forgejo", "content_type": "json"},
      |  "events": ["push", "forgejo_future_event"],
      |  "created_at": "2026-02-09T10:11:12Z"
      |}""".stripMargin

  /** The same hook, from an instance that echoes both credentials back. Neither may reach a caller. */
  val LeakyHookBody: String =
    """{
      |  "id": 7,
      |  "type": "forgejo",
      |  "config": {"url": "https://ci.example/forgejo", "content_type": "json", "secret": "hunter2"},
      |  "authorization_header": "Bearer sekrit"
      |}""".stripMargin
