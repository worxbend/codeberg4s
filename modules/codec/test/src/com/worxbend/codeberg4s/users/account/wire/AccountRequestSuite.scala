package com.worxbend.codeberg4s.users.account.wire

import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.users.account.AvatarImage
import com.worxbend.codeberg4s.users.account.CreateRepository
import com.worxbend.codeberg4s.users.account.EmailAddress
import com.worxbend.codeberg4s.users.account.OAuth2ApplicationDefinition
import com.worxbend.codeberg4s.users.account.ObjectFormat
import com.worxbend.codeberg4s.users.account.QuotaSubject
import com.worxbend.codeberg4s.users.account.RepositoryOrder
import com.worxbend.codeberg4s.users.account.TrustModel
import com.worxbend.codeberg4s.users.account.UpdateUserSettings

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64

/** The exact bytes and query strings this group sends.
  *
  * Rendering is asserted here rather than through a stub backend, because a request body is a value and comparing
  * strings is the clearest way to pin a wire spelling. `JsonValue.Obj` preserves insertion order, so the expected
  * strings below are stable.
  */
final class AccountRequestSuite extends FunSuite:

  // --- OAuth2 applications --------------------------------------------------

  test("an application body always states all three properties, because the update has no notion of an absent key"):
    assertEquals(
      AccountOptionDto.renderApplication(definition("deploy-bot")),
      """{"name":"deploy-bot","redirect_uris":[],"confidential_client":false}""",
    )

  test("an application body carries the redirect destinations in the order the caller wrote them"):
    val rendered = AccountOptionDto.renderApplication(
      definition("deploy-bot").redirectingTo("https://b.example", "https://a.example").confidential
    )

    assertEquals(
      rendered,
      """{"name":"deploy-bot","redirect_uris":["https://b.example","https://a.example"],""" +
        """"confidential_client":true}""",
    )

  // --- avatar ---------------------------------------------------------------

  test("an avatar body is one base64 string, not a multipart upload"):
    val image = AvatarImage.ofBytes("PNGDATA".getBytes(StandardCharsets.UTF_8))
    val blob  = Base64.getEncoder.encodeToString("PNGDATA".getBytes(StandardCharsets.UTF_8))

    assertEquals(AccountOptionDto.renderAvatar(image), s"""{"${AccountOptionDto.ImageKey}":"$blob"}""")

  // --- emails ---------------------------------------------------------------

  test("both email bodies send their addresses under the same key, because both models declare the same one"):
    val addresses = Vector(address("a@example.org"), address("b@example.org"))

    assertEquals(
      AccountOptionDto.renderEmails(addresses),
      """{"emails":["a@example.org","b@example.org"]}""",
    )

  test("the email key is the one this suite names, so a rename cannot pass unnoticed"):
    assertEquals(AccountOptionDto.EmailsKey, "emails")

  // --- settings -------------------------------------------------------------

  test("the empty settings command renders as an object that changes nothing"):
    assertEquals(AccountOptionDto.renderSettings(UpdateUserSettings.Empty), "{}")

  test("only what the caller set is emitted, in the order the renderer states"):
    val command = UpdateUserSettings.Empty.named("A Maintainer").hidingEmail

    assertEquals(AccountOptionDto.renderSettings(command), """{"full_name":"A Maintainer","hide_email":true}""")

  test("clearing a text field emits the empty string, which is a different request from omitting the key"):
    assertEquals(
      AccountOptionDto.renderSettings(UpdateUserSettings.Empty.describedAs("")),
      """{"description":""}""",
    )

  test("every settings key the spec declares has a spelling, and it is the snake_case one"):
    val everything = UpdateUserSettings.Empty
      .named("n")
      .linkingTo("w")
      .locatedIn("l")
      .describedAs("d")
      .withPronouns("p")
      .inLanguage("en")
      .themed("t")
      .viewingDiffsAs("unified")
      .hidingEmail
      .hidingActivity
      .hidingPronouns
      .showingRepoUnitHints

    assertEquals(
      AccountOptionDto.renderSettings(everything),
      """{"full_name":"n","website":"w","location":"l","description":"d","pronouns":"p","language":"en",""" +
        """"theme":"t","diff_view_style":"unified","hide_email":true,"hide_activity":true,"hide_pronouns":true,""" +
        """"enable_repo_unit_hints":true}""",
    )

  // --- repository creation --------------------------------------------------

  test("a repository body states the name and the three flags, and nothing the caller left alone"):
    assertEquals(
      AccountOptionDto.renderRepository(CreateRepository.named(repoName("codeberg4s"))),
      """{"name":"codeberg4s","private":false,"template":false,"auto_init":false}""",
    )

  test("a fully specified repository body carries every property the spec declares"):
    val command = CreateRepository
      .named(repoName("codeberg4s"))
      .describedAs("a client")
      .keptPrivate
      .asTemplate
      .initialised
      .onDefaultBranch(branchName("main"))
      .ignoring("Scala")
      .licensedAs("MIT")
      .withReadme("Default")
      .withIssueLabels("Default")
      .usingObjectFormat(ObjectFormat.Sha256)
      .trusting(TrustModel.CollaboratorCommitter)

    assertEquals(
      AccountOptionDto.renderRepository(command),
      """{"name":"codeberg4s","description":"a client","private":true,"template":true,"auto_init":true,""" +
        """"default_branch":"main","gitignores":"Scala","license":"MIT","readme":"Default",""" +
        """"issue_labels":"Default","object_format_name":"sha256","trust_model":"collaboratorcommitter"}""",
    )

  // --- queries --------------------------------------------------------------

  test("paging always sends both parameters, page first"):
    assertEquals(AccountQueries.paging(window(2, 25)), List("page" -> "2", "limit" -> "25"))

  test("the default ordering sends no order_by, so the instance chooses"):
    assertEquals(AccountQueries.repositoryOrder(RepositoryOrder.Default), Nil)

  test("a stated ordering sends its own wire spelling"):
    assertEquals(
      AccountQueries.repositoryOrder(RepositoryOrder.ReverseAlphabetically),
      List("order_by" -> "reversealphabetically"),
    )

  test("the quota check always sends its subject, which the spec marks required"):
    assertEquals(AccountQueries.quotaCheck(subject("size:all")), List("subject" -> "size:all"))

  private def definition(name: String): OAuth2ApplicationDefinition =
    orFail(OAuth2ApplicationDefinition.named(name))

  private def address(value: String): EmailAddress =
    orFail(EmailAddress.from(value))

  private def subject(value: String): QuotaSubject =
    orFail(QuotaSubject.from(value))

  private def repoName(value: String): RepoName =
    orFail(RepoName.from(value))

  private def branchName(value: String): BranchName =
    orFail(BranchName.from(value))

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
