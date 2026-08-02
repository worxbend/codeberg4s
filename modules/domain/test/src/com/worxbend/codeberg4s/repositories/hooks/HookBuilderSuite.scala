package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.FileContent

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64

/** What the hook and wiki builders change, and what they leave alone.
  *
  * Each builder is applied to a command with '''every''' field already stated, and compared against that same command
  * with one field replaced. On a fresh command the interesting defect — a builder that clears a sibling — cannot be
  * seen, and on these two commands the sibling is often a credential: a builder that dropped the signing secret would
  * quietly create an unsigned hook, which is a hook whose deliveries nobody can authenticate.
  */
final class HookBuilderSuite extends FunSuite:

  private val Signing: HookSecret = secret("s3cr3t-signing-key")

  private val Bearer: HookSecret = secret("token abc123")

  private val Replacement: HookConfig = HookConfig.of("https://elsewhere.example/hook", HookContentType.Form)

  // --- CreateHook -----------------------------------------------------------

  test("every create-hook builder sets its own field and leaves every sibling alone"):
    val command = populatedCreate

    assertEquals(command.subscribingTo(HookEvent.Release), command.copy(events = Vector(HookEvent.Release)))
    assertEquals(command.filteringBranches("release/*"), command.copy(branchFilter = Some("release/*")))
    assertEquals(command.signedWith(Bearer), command.copy(secret = Some(Bearer)))
    assertEquals(command.authorization(Signing), command.copy(authorizationHeader = Some(Signing)))
    assertEquals(command.activated, command.copy(isActive = true))
    assertEquals(
      command.configured("channel", "#builds"),
      command.copy(config = command.config.withEntry("channel", "#builds")),
    )

  test("adding a config entry keeps the two entries Forgejo requires of every hook"):
    val command = CreateHook
      .to(HookType.Slack, "https://hooks.slack.example/x", HookContentType.Json)
      .configured("channel", "#builds")
      .configured("username", "forgejo")

    assertEquals(command.config.url, Some("https://hooks.slack.example/x"))
    assertEquals(command.config.contentType, Some(HookContentType.Json))
    assertEquals(command.config.valueOf("channel"), Some("#builds"))
    assertEquals(command.config.valueOf("username"), Some("forgejo"))

  test("a config entry naming a credential is dropped rather than stored, even through the command builder"):
    val command = populatedCreate.configured("secret", "hunter2").configured("authorization_header", "token abc")

    assertEquals(command.config.valueOf(HookConfig.SecretKey), None)
    assertEquals(command.config.valueOf(HookConfig.AuthorizationHeaderKey), None)
    assert(!command.toString.contains("hunter2"), s"a credential reached the config: $command")

  test("the signing secret and the authorization header are separate fields, so one does not overwrite the other"):
    val command = CreateHook
      .to(HookType.Forgejo, "https://ci.example/hook", HookContentType.Json)
      .signedWith(Signing)
      .authorization(Bearer)

    assertEquals(command.secret, Some(Signing))
    assertEquals(command.authorizationHeader, Some(Bearer))

  test("subscribing replaces the subscriptions rather than adding to them"):
    val command = populatedCreate.subscribingTo(HookEvent.Push, HookEvent.Release).subscribingTo(HookEvent.Wiki)

    assertEquals(command.events, Vector(HookEvent.Wiki))

  // --- EditHook -------------------------------------------------------------

  test("every edit-hook builder sets its own field and leaves every sibling alone"):
    val edit = populatedEdit

    assertEquals(edit.configuredAs(Replacement), edit.copy(config = Some(Replacement)))
    assertEquals(edit.subscribingTo(HookEvent.Wiki), edit.copy(events = Some(Vector(HookEvent.Wiki))))
    assertEquals(edit.filteringBranches("release/*"), edit.copy(branchFilter = Some("release/*")))
    assertEquals(edit.signedWith(Bearer), edit.copy(secret = Some(Bearer)))
    assertEquals(edit.authorization(Signing), edit.copy(authorizationHeader = Some(Signing)))
    assertEquals(edit.activated, edit.copy(isActive = Some(true)))
    assertEquals(edit.deactivated, edit.copy(isActive = Some(false)))

  test("replacing the config of an edit does not restate the subscriptions, which would be a second change"):
    val edit = EditHook.Empty.configuredAs(Replacement)

    assertEquals(edit.config.flatMap(_.url), Some("https://elsewhere.example/hook"))
    assertEquals(edit.events, None)
    assertEquals(edit.isActive, None)

  test("a branch filter is stated on its own, so narrowing one hook does not deactivate it"):
    val edit = EditHook.Empty.filteringBranches("main")

    assertEquals(edit.branchFilter, Some("main"))
    assertEquals(edit.isActive, None)
    assertEquals(edit.secret, None)

  test("rotating the signing secret leaves the subscriptions and the config unmentioned"):
    val edit = EditHook.Empty.signedWith(Signing)

    assertEquals(edit.secret, Some(Signing))
    assertEquals(edit.config, None)
    assertEquals(edit.events, None)
    assert(!edit.toString.contains("signing-key"), s"the generated toString leaked the secret: $edit")

  // --- git hooks ------------------------------------------------------------

  test("a git hook command stores the script verbatim, newlines and all"):
    val script = "#!/bin/sh\nexec echo blocked\n"

    assertEquals(EditGitHook.of(script).content, script)

  test("an empty git hook script is how a hook is emptied, so it is not refused"):
    assertEquals(EditGitHook.of("").content, "")

  // --- wiki pages -----------------------------------------------------------

  test("an edit-page commit message is stated separately from the content it accompanies"):
    val command = EditWikiPage.ofText("hello").withMessage("clarify the deploy step")

    assertEquals(command.message, Some("clarify the deploy step"))
    assertEquals(command.content.text, Some("hello"))
    assertEquals(command.renamedTo, None)
    assertEquals(EditWikiPage.ofText("hello").message, None)

  test("moving a page and messaging the move are separate builders, and neither undoes the other"):
    val page    = orFail(WikiPageName.from("Deployment"))
    val command = EditWikiPage.ofText("hello").movedTo(page).withMessage("rename")

    assertEquals(command.renamedTo.map(_.value), Some("Deployment"))
    assertEquals(command.message, Some("rename"))
    assertEquals(command.content.text, Some("hello"))

  test("an edit-page command built from already-encoded content keeps that content and states nothing else"):
    val encoded = FileContent.Base64(JavaBase64.getEncoder.encodeToString("hi".getBytes(StandardCharsets.UTF_8)))
    val command = orFail(EditWikiPage.of(encoded))

    assertEquals(command.content.text, Some("hi"))
    assertEquals(command.renamedTo, None)
    assertEquals(command.message, None)

  private def populatedCreate: CreateHook =
    CreateHook(
      hookType            = HookType.Forgejo,
      config              = HookConfig.of("https://ci.example/hook", HookContentType.Json),
      events              = Vector(HookEvent.Push),
      branchFilter        = Some("main"),
      secret              = Some(Signing),
      authorizationHeader = Some(Bearer),
      isActive            = false,
    )

  private def populatedEdit: EditHook =
    EditHook(
      config              = Some(HookConfig.of("https://ci.example/hook", HookContentType.Json)),
      events              = Some(Vector(HookEvent.Push)),
      branchFilter        = Some("main"),
      secret              = Some(Signing),
      authorizationHeader = Some(Bearer),
      isActive            = None,
    )

  private def secret(value: String): HookSecret = orFail(HookSecret.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
