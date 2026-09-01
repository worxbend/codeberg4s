package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.repositories.hooks.GitHook
import com.worxbend.codeberg4s.repositories.hooks.HookConfig
import com.worxbend.codeberg4s.repositories.hooks.HookContentType
import com.worxbend.codeberg4s.repositories.hooks.HookEvent
import com.worxbend.codeberg4s.repositories.hooks.HookType
import com.worxbend.codeberg4s.repositories.hooks.Webhook

import munit.FunSuite

import java.time.Instant

/** Decoding the two hook models, and the one thing a hook payload must never be able to smuggle through.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured.''' Every hook route requires a token and the
  * golden harvest was anonymous, so no fixture exists; see [[WebhookDto]].
  */
final class WebhookDtoSuite extends FunSuite:

  // --- Hook -----------------------------------------------------------------

  test("a webhook decodes with its identifier, type, config, events and timestamps"):
    val hook = domainHook(WebhookDtoSuite.Full)

    assertEquals(hook.id.value, 4242L)
    assertEquals(hook.hookType, Some(HookType.Forgejo))
    assertEquals(hook.configuration.url, Some("https://ci.example/forgejo"))
    assertEquals(hook.configuration.contentType, Some(HookContentType.Json))
    assertEquals(hook.events, Vector(HookEvent.Push, HookEvent.PullRequest))
    assertEquals(hook.branchFilter, Some("main"))
    assertEquals(hook.isActive, Some(true))
    assertEquals(hook.url, Some("https://forge.example/api/v1/repos/o/r/hooks/4242"))
    assertEquals(hook.createdAt, Some(Instant.parse("2026-07-30T19:14:15Z")))
    assertEquals(hook.updatedAt, Some(Instant.parse("2026-07-31T19:14:15Z")))

  test("an event name outside the documented vocabulary is kept rather than dropped"):
    val hook = domainHook("""{"id":1,"events":["push","action_run_failure"]}""")

    assertEquals(hook.events, Vector(HookEvent.Push, HookEvent.Other("action_run_failure")))

  test("a hook type outside the spec's enum is kept too"):
    assertEquals(domainHook("""{"id":1,"type":"matrix"}""").hookType, Some(HookType.Other("matrix")))

  test("a webhook without an id cannot be converted, because nothing else addresses it"):
    assertEquals(hookFailure("""{"type":"forgejo"}"""), Some("$.id"))

  test("a webhook whose id is not a usable row id cannot be converted"):
    assertEquals(hookFailure("""{"id":0}"""), Some("$.id"))

  test("a secret the instance echoes back cannot reach the model"):
    val hook = domainHook("""{"id":1,"config":{"url":"https://ci.example","secret":"hunter2"}}""")

    assertEquals(hook.configuration.valueOf(HookConfig.SecretKey), None)
    assert(!hook.toString.contains("hunter2"), s"a secret reached the webhook model: $hook")

  test("an authorization header the instance echoes back cannot reach the model either"):
    val hook = domainHook("""{"id":1,"authorization_header":"token abc123","config":{"authorization_header":"t"}}""")

    assert(!hook.toString.contains("abc123"), s"an authorization header reached the webhook model: $hook")
    assertEquals(hook.configuration.valueOf(HookConfig.AuthorizationHeaderKey), None)

  test("a top-level content_type fills in when the config omits one"):
    val hook = domainHook("""{"id":1,"content_type":"form","config":{"url":"https://ci.example"}}""")

    assertEquals(hook.configuration.contentType, Some(HookContentType.Form))

  test("the config entry wins when both spellings are present"):
    val hook = domainHook("""{"id":1,"content_type":"form","config":{"content_type":"json"}}""")

    assertEquals(hook.configuration.contentType, Some(HookContentType.Json))

  test("a config entry that is not a string is dropped rather than guessed at"):
    val hook = domainHook("""{"id":1,"config":{"url":"https://ci.example","port":8080}}""")

    assertEquals(hook.configuration.valueOf("port"), None)
    assertEquals(hook.configuration.url, Some("https://ci.example"))

  test("a config that arrives as JSON null is an empty config, not a failure"):
    assertEquals(domainHook("""{"id":1,"config":null}""").configuration.entries, Map.empty[String, String])

  test("events that arrive as JSON null are an empty subscription list, not a failure"):
    assertEquals(domainHook("""{"id":1,"events":null}""").events, Vector.empty[HookEvent])

  test("JSON null and an absent key decode identically for every optional webhook field"):
    WebhookDtoSuite.HookOptionalKeys.foreach: key =>
      assertEquals(
        decodeHook(s"""{"id":1,"$key":null}"""),
        decodeHook("""{"id":1}"""),
        s"'$key' distinguished null from absent",
      )

  test("a bad element of a hook array reports its own position"):
    val dtos = Json.decode[Vector[WebhookDto]]("""[{"id":1},{"type":"forgejo"}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(WireModel.all(JsonPath.Root, dtos).swap.toOption.map(_.path.render), Some("$[1].id"))

  // --- GitHook --------------------------------------------------------------

  test("a Git hook decodes with its name, its flag and its script"):
    val hook = domainGitHook("""{"name":"pre-receive","is_active":true,"content":"#!/bin/sh\nexit 0\n"}""")

    assertEquals(hook.name.value, "pre-receive")
    assertEquals(hook.isActive, Some(true))
    assertEquals(hook.content, Some("#!/bin/sh\nexit 0\n"))

  test("a Git hook with an empty script keeps the empty string, which is not the same as having none"):
    assertEquals(domainGitHook("""{"name":"update","content":""}""").content, Some(""))

  test("a Git hook without a name cannot be converted"):
    assertEquals(gitHookFailure("""{"is_active":false}"""), Some("$.name"))

  test("a Git hook whose name could forge a path cannot be converted"):
    assertEquals(gitHookFailure("""{"name":"../../etc/passwd"}"""), Some("$.name"))

  test("JSON null and an absent key decode identically for every optional Git hook field"):
    Vector("is_active", "content").foreach: key =>
      assertEquals(
        decodeGitHook(s"""{"name":"update","$key":null}"""),
        decodeGitHook("""{"name":"update"}"""),
        s"'$key' distinguished null from absent",
      )

  test("a bad element of a Git hook array reports its own position"):
    val dtos = Json.decode[Vector[GitHookDto]]("""[{"name":"update"},{"content":"x"}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(WireModel.all(JsonPath.Root, dtos).swap.toOption.map(_.path.render), Some("$[1].name"))

  private def decodeHook(body: String): WebhookDto =
    Json.decode[WebhookDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainHook(body: String): Webhook =
    decodeHook(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def hookFailure(body: String): Option[String] =
    decodeHook(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeGitHook(body: String): GitHookDto =
    Json.decode[GitHookDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainGitHook(body: String): GitHook =
    decodeGitHook(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def gitHookFailure(body: String): Option[String] =
    decodeGitHook(body).toDomain.swap.toOption.map(_.path.render)

/** The payloads this suite decodes, kept out of the test bodies so each test reads as one behaviour. */
object WebhookDtoSuite:

  /** Every property `spec/swagger.v1.json` declares on `Hook`, with plausible values. */
  private val Full: String =
    """{
      |  "id": 4242,
      |  "type": "forgejo",
      |  "config": {"url": "https://ci.example/forgejo", "content_type": "json"},
      |  "content_type": "json",
      |  "events": ["push", "pull_request"],
      |  "url": "https://forge.example/api/v1/repos/o/r/hooks/4242",
      |  "branch_filter": "main",
      |  "active": true,
      |  "authorization_header": "token abc123",
      |  "created_at": "2026-07-30T21:14:15+02:00",
      |  "updated_at": "2026-07-31T21:14:15+02:00"
      |}""".stripMargin

  /** Every wire key of `Hook` this DTO reads, apart from the one required field. */
  private val HookOptionalKeys: Vector[String] =
    Vector("type", "config", "content_type", "events", "url", "branch_filter", "active", "created_at", "updated_at")
