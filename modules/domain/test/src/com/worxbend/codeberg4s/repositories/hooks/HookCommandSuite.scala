package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.FileContent

import munit.FunSuite

import java.nio.charset.StandardCharsets

/** The commands this group sends, and the two decisions they encode.
  *
  * The first is that a builder leaves alone what the caller did not mention, so a command carries the difference
  * between "unsubscribe from everything" and "do not touch the subscriptions". The second is that wiki content is
  * base64 and nothing else can reach a key named `content_base64`.
  */
final class HookCommandSuite extends FunSuite:

  // --- CreateHook -----------------------------------------------------------

  test("a create command starts with the three values Forgejo requires and nothing else"):
    val command = CreateHook.to(HookType.Forgejo, "https://ci.example/hook", HookContentType.Json)

    assertEquals(command.config.url, Some("https://ci.example/hook"))
    assertEquals(command.config.contentType, Some(HookContentType.Json))
    assertEquals(command.events, Vector.empty[HookEvent])
    assertEquals(command.isActive, false)
    assertEquals(command.secret, None)

  test("a create command subscribes to exactly what it was given, repeats included"):
    val command = create.subscribingTo(HookEvent.Push, HookEvent.Push, HookEvent.Release)

    assertEquals(command.events, Vector(HookEvent.Push, HookEvent.Push, HookEvent.Release))

  test("a create command's builders compose without disturbing one another"):
    val command = create.subscribingTo(HookEvent.Push).filteringBranches("main").activated

    assertEquals(command.branchFilter, Some("main"))
    assertEquals(command.isActive, true)
    assertEquals(command.events, Vector(HookEvent.Push))
    assertEquals(command.config.url, Some("https://ci.example/hook"))

  // --- EditHook -------------------------------------------------------------

  test("the empty edit command mentions nothing at all"):
    assertEquals(EditHook.Empty.config, None)
    assertEquals(EditHook.Empty.events, None)
    assertEquals(EditHook.Empty.isActive, None)

  test("unsubscribing from everything is a stated empty list, not an absent one"):
    assertEquals(EditHook.Empty.subscribingTo().events, Some(Vector.empty[HookEvent]))

  test("deactivating states false rather than leaving the field alone"):
    assertEquals(EditHook.Empty.deactivated.isActive, Some(false))
    assertEquals(EditHook.Empty.activated.isActive, Some(true))

  // --- wiki content ---------------------------------------------------------

  test("wiki content encodes text as UTF-8 base64, and decodes back to the same text"):
    val content = WikiContent.ofText("# Deployment\n\nRun make deploy.")

    assertEquals(content.text, Some("# Deployment\n\nRun make deploy."))

  test("wiki content encodes text that is not ASCII without loss"):
    assertEquals(WikiContent.ofText("Grüße, 世界").text, Some("Grüße, 世界"))

  test("wiki content encodes bytes for a page that is not text"):
    val bytes   = Array[Byte](0, 1, 2, 3)
    val decoded = WikiContent.ofBytes(bytes).decoded

    assertEquals(decoded.map(_.toVector), Some(bytes.toVector))

  test("the encoder emits no line breaks, so a rendered body is reproducible"):
    val long = WikiContent.ofText("x".repeat(500))

    assert(!long.raw.contains("\n"), "the base64 payload carried a line break")

  // --- wiki commands --------------------------------------------------------

  test("a create-page command from text carries the title and the encoded content"):
    val command = CreateWikiPage.ofText(page, "hello").withMessage("start the page")

    assertEquals(command.title.value, "Home")
    assertEquals(command.content.text, Some("hello"))
    assertEquals(command.message, Some("start the page"))

  test("a create-page command accepts content that is already base64"):
    val encoded = FileContent.Base64(java.util.Base64.getEncoder.encodeToString("hi".getBytes(StandardCharsets.UTF_8)))

    assertEquals(orFail(CreateWikiPage.of(page, encoded)).content.text, Some("hi"))

  test("a create-page command refuses content whose encoding this library does not know"):
    val opaque = FileContent.Opaque(Some("gzip"), "H4sIA")

    assertEquals(CreateWikiPage.of(page, opaque).swap.toOption.map(_.field), Some("content"))

  test("an edit-page command leaves the title alone unless it is moved"):
    assertEquals(EditWikiPage.ofText("hello").renamedTo, None)
    assertEquals(EditWikiPage.ofText("hello").movedTo(page).renamedTo.map(_.value), Some("Home"))

  test("an edit-page command refuses content whose encoding this library does not know"):
    assertEquals(EditWikiPage.of(FileContent.Opaque(None, "??")).swap.toOption.map(_.field), Some("content"))

  private def create: CreateHook =
    CreateHook.to(HookType.Forgejo, "https://ci.example/hook", HookContentType.Json)

  private def page: WikiPageName =
    orFail(WikiPageName.from("Home"))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
