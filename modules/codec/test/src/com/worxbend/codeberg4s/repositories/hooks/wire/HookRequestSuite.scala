package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.hooks.CreateHook
import com.worxbend.codeberg4s.repositories.hooks.CreateWikiPage
import com.worxbend.codeberg4s.repositories.hooks.EditGitHook
import com.worxbend.codeberg4s.repositories.hooks.EditHook
import com.worxbend.codeberg4s.repositories.hooks.EditWikiPage
import com.worxbend.codeberg4s.repositories.hooks.HookContentType
import com.worxbend.codeberg4s.repositories.hooks.HookEvent
import com.worxbend.codeberg4s.repositories.hooks.HookSecret
import com.worxbend.codeberg4s.repositories.hooks.HookType
import com.worxbend.codeberg4s.repositories.hooks.RepositoryFlag
import com.worxbend.codeberg4s.repositories.hooks.WikiPageName

import munit.FunSuite

/** The bodies and query strings this group sends.
  *
  * Rendering is asserted directly rather than through a stub backend, because a request body is a value and comparing
  * values is cheaper and clearer than inspecting an interaction. The two things worth guarding here are that only what
  * a caller stated is emitted, and that a credential reaches the bytes of the request and nothing else.
  */
final class HookRequestSuite extends FunSuite:

  // --- CreateHookOption -----------------------------------------------------

  test("a create body carries the required type and config, and states active even at its default"):
    assertEquals(
      HookOptionDto.renderCreate(create),
      """{"type":"forgejo","config":{"content_type":"json","url":"https://ci.example/hook"},"active":false}""",
    )

  test("a create body emits events only when the caller subscribed to some"):
    val body = HookOptionDto.renderCreate(create.subscribingTo(HookEvent.Push, HookEvent.Release))

    assert(body.contains("""{"events":["push","release"]""".stripPrefix("{")), body)

  test("a create body puts the signing secret inside config, where Forgejo reads it from"):
    val body = HookOptionDto.renderCreate(create.signedWith(secret("hunter2")))

    assertEquals(
      body,
      """{"type":"forgejo","config":{"content_type":"json","url":"https://ci.example/hook","secret":"hunter2"},""" +
        """"active":false}""",
    )

  test("a create body puts the authorization header at the top level, not in config"):
    val body = HookOptionDto.renderCreate(create.authorization(secret("token abc123")))

    assert(body.contains(""""authorization_header":"token abc123""""), body)
    assert(!body.contains("""config":{"authorization_header"""), body)

  test("a secret containing a quote and a newline is escaped rather than breaking the body"):
    val body = HookOptionDto.renderCreate(create.signedWith(secret("a\"b\nc")))

    assert(body.contains("""\"b\nc"""), body)

  test("config entries render in key order, so the body is reproducible"):
    val body = HookOptionDto.renderCreate(create.configured("channel", "#builds").configured("username", "forge"))

    assert(
      body.contains(""""channel":"#builds","content_type":"json","url":"...","username":"forge"""".replace(
        "...",
        "https://ci.example/hook",
      )),
      body,
    )

  test("a config entry naming a credential is dropped rather than sent"):
    val body = HookOptionDto.renderCreate(create.configured("secret", "hunter2"))

    assert(!body.contains("hunter2"), body)

  // --- EditHookOption -------------------------------------------------------

  test("an empty edit renders as an empty object, so it changes nothing"):
    assertEquals(HookOptionDto.renderEdit(EditHook.Empty), "{}")

  test("an edit emits an empty events array when the caller asked to unsubscribe from everything"):
    assertEquals(HookOptionDto.renderEdit(EditHook.Empty.subscribingTo()), """{"events":[]}""")

  test("an edit that only sets a secret still sends a config object holding just it"):
    assertEquals(
      HookOptionDto.renderEdit(EditHook.Empty.signedWith(secret("hunter2"))),
      """{"config":{"secret":"hunter2"}}""",
    )

  test("an edit emits only the keys the caller mentioned"):
    assertEquals(
      HookOptionDto.renderEdit(EditHook.Empty.filteringBranches("main").deactivated),
      """{"branch_filter":"main","active":false}""",
    )

  // --- EditGitHookOption ----------------------------------------------------

  test("a Git hook edit always sends content, including when it is empty"):
    assertEquals(HookOptionDto.renderEditGit(EditGitHook.of("")), """{"content":""}""")
    assertEquals(HookOptionDto.renderEditGit(EditGitHook.of("#!/bin/sh\n")), """{"content":"#!/bin/sh\n"}""")

  // --- CreateWikiPageOptions ------------------------------------------------

  test("a wiki create body always names the page and sends its base64 content"):
    assertEquals(
      WikiPageOptionsDto.renderCreate(CreateWikiPage.ofText(page, "hi")),
      """{"title":"Home","content_base64":"aGk="}""",
    )

  test("a wiki create body carries the commit message when the caller wrote one"):
    assertEquals(
      WikiPageOptionsDto.renderCreate(CreateWikiPage.ofText(page, "hi").withMessage("start")),
      """{"title":"Home","content_base64":"aGk=","message":"start"}""",
    )

  test("a wiki edit body omits the title, because an empty one would mean 'leave unchanged'"):
    assertEquals(
      WikiPageOptionsDto.renderEdit(EditWikiPage.ofText("hi")),
      """{"content_base64":"aGk="}""",
    )

  test("a wiki edit body sends the title only when the edit is a rename"):
    assertEquals(
      WikiPageOptionsDto.renderEdit(EditWikiPage.ofText("hi").movedTo(page)),
      """{"title":"Home","content_base64":"aGk="}""",
    )

  // --- ReplaceFlagsOption ---------------------------------------------------

  test("a flag replacement sends the caller's list in the order they built it"):
    assertEquals(
      RepositoryFlagWire.renderReplace(Vector(flag("featured"), flag("archived-2024"))),
      """{"flags":["featured","archived-2024"]}""",
    )

  test("an empty flag replacement asks for every flag to be cleared"):
    assertEquals(RepositoryFlagWire.renderReplace(Vector.empty), """{"flags":[]}""")

  test("a flag listing converts every element and reports the position of a bad one"):
    assertEquals(
      RepositoryFlagWire.toDomainAll(JsonPath.Root, Vector("featured", "ok")).map(_.map(_.value)),
      Right(Vector("featured", "ok")),
    )
    assertEquals(
      RepositoryFlagWire.toDomainAll(JsonPath.Root, Vector("featured", "a/b")).swap.toOption.map(_.path.render),
      Some("$[1]"),
    )

  // --- query strings --------------------------------------------------------

  test("a hook test sends a ref only when the caller named one"):
    assertEquals(HookQueries.hookTest(Some("refs/heads/main")), List("ref" -> "refs/heads/main"))
    assertEquals(HookQueries.hookTest(None), Nil)

  private def create: CreateHook =
    CreateHook.to(HookType.Forgejo, "https://ci.example/hook", HookContentType.Json)

  private def page: WikiPageName =
    orFail(WikiPageName.from("Home"))

  private def secret(value: String): HookSecret =
    orFail(HookSecret.from(value))

  private def flag(value: String): RepositoryFlag =
    orFail(RepositoryFlag.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
