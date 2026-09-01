package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName

import munit.FunSuite

/** The commands this group sends, and what a caller can express with them.
  *
  * Every assertion here is about a decision a caller can observe without a network: what a builder sets, what a default
  * is. The wire rendering of these commands is asserted in `modules/codec`; this suite is about what they mean.
  */
final class AccountCommandSuite extends FunSuite:

  // --- OAuth2 application definitions ---------------------------------------

  test("an application definition starts from its name and nothing else"):
    val definition = definitionNamed("deploy-bot")

    assertEquals(definition.name, "deploy-bot")
    assertEquals(definition.redirectUris, Vector.empty[String])
    assertEquals(definition.isConfidentialClient, false)

  test("an application definition trims its name"):
    assertEquals(definitionNamed("  deploy-bot  ").name, "deploy-bot")

  test("an application definition rejects a blank name"):
    assertEquals(
      OAuth2ApplicationDefinition.named("   ").swap.toOption.map(_.field),
      Some("oauth2ApplicationName"),
    )

  test("redirect destinations replace whatever was there and keep repeats as written"):
    val definition = definitionNamed("deploy-bot").redirectingTo("https://a.example", "https://a.example")

    assertEquals(definition.redirectUris, Vector("https://a.example", "https://a.example"))

  test("a definition can be moved between confidential and public"):
    assertEquals(definitionNamed("deploy-bot").confidential.isConfidentialClient, true)
    assertEquals(definitionNamed("deploy-bot").confidential.publicClient.isConfidentialClient, false)

  // --- settings -------------------------------------------------------------

  test("the empty settings command changes nothing"):
    assertEquals(UpdateUserSettings.Empty.fullName, None)
    assertEquals(UpdateUserSettings.Empty.hidesEmail, None)

  test("clearing a text field is spelled differently from leaving it alone"):
    assertEquals(UpdateUserSettings.Empty.describedAs("").description, Some(""))
    assertEquals(UpdateUserSettings.Empty.description, None)

  test("each privacy flag has both a hiding and a showing spelling"):
    assertEquals(UpdateUserSettings.Empty.hidingEmail.hidesEmail, Some(true))
    assertEquals(UpdateUserSettings.Empty.showingEmail.hidesEmail, Some(false))
    assertEquals(UpdateUserSettings.Empty.hidingActivity.hidesActivity, Some(true))
    assertEquals(UpdateUserSettings.Empty.showingActivity.hidesActivity, Some(false))
    assertEquals(UpdateUserSettings.Empty.hidingPronouns.hidesPronouns, Some(true))
    assertEquals(UpdateUserSettings.Empty.showingPronouns.hidesPronouns, Some(false))

  test("the repository unit hint flag has both spellings too"):
    assertEquals(UpdateUserSettings.Empty.showingRepoUnitHints.showsRepoUnitHints, Some(true))
    assertEquals(UpdateUserSettings.Empty.hidingRepoUnitHints.showsRepoUnitHints, Some(false))

  test("the text builders each set their own field and no other"):
    val command = UpdateUserSettings.Empty
      .named("A Maintainer")
      .linkingTo("https://example.org")
      .locatedIn("Somewhere")
      .withPronouns("they/them")
      .inLanguage("en-US")
      .themed("forgejo-dark")
      .viewingDiffsAs("unified")

    assertEquals(command.fullName, Some("A Maintainer"))
    assertEquals(command.website, Some("https://example.org"))
    assertEquals(command.location, Some("Somewhere"))
    assertEquals(command.pronouns, Some("they/them"))
    assertEquals(command.language, Some("en-US"))
    assertEquals(command.theme, Some("forgejo-dark"))
    assertEquals(command.diffViewStyle, Some("unified"))
    assertEquals(command.description, None)

  // --- repository creation --------------------------------------------------

  test("a repository creation starts from its name, public and uninitialised"):
    val command = CreateRepository.named(repoName("codeberg4s"))

    assertEquals(command.isPrivate, false)
    assertEquals(command.isTemplate, false)
    assertEquals(command.autoInit, false)
    assertEquals(command.defaultBranch, None)
    assertEquals(command.objectFormat, None)

  test("the visibility builders are each other's inverse"):
    val command = CreateRepository.named(repoName("codeberg4s"))

    assertEquals(command.keptPrivate.isPrivate, true)
    assertEquals(command.keptPrivate.keptPublic.isPrivate, false)

  test("the seeding builders each set their own field"):
    val command = CreateRepository
      .named(repoName("codeberg4s"))
      .describedAs("a client")
      .initialised
      .onDefaultBranch(branchName("main"))
      .ignoring("Scala,JetBrains")
      .licensedAs("MIT")
      .withReadme("Default")
      .withIssueLabels("Default")
      .asTemplate
      .usingObjectFormat(ObjectFormat.Sha256)
      .trusting(TrustModel.Committer)

    assertEquals(command.description, Some("a client"))
    assertEquals(command.autoInit, true)
    assertEquals(command.defaultBranch.map(_.value), Some("main"))
    assertEquals(command.gitignores, Some("Scala,JetBrains"))
    assertEquals(command.license, Some("MIT"))
    assertEquals(command.readme, Some("Default"))
    assertEquals(command.issueLabels, Some("Default"))
    assertEquals(command.isTemplate, true)
    assertEquals(command.objectFormat, Some(ObjectFormat.Sha256))
    assertEquals(command.trustModel, Some(TrustModel.Committer))

  private def definitionNamed(value: String): OAuth2ApplicationDefinition =
    orFail(OAuth2ApplicationDefinition.named(value))

  private def repoName(value: String): RepoName =
    orFail(RepoName.from(value))

  private def branchName(value: String): BranchName =
    orFail(BranchName.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
