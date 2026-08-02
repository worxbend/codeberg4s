package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.RepoName

import munit.FunSuite

/** What [[CreateRepository]]'s builders change, and — the part that matters — what they leave alone.
  *
  * Every builder is applied to a command that already has '''every''' property set, and the result is compared against
  * that same command with one field replaced. A builder that reset a sibling on its way past fails here; applied to a
  * fresh command it would look correct, because a field that was never set cannot be seen to be cleared.
  *
  * The other subject is the one hazard the type's own note names: the README, `.gitignore` and licence templates do
  * nothing without an initial commit, and no builder turns that on as a favour.
  */
final class CreateRepositoryBuilderSuite extends FunSuite:

  private val Trunk: BranchName = orFail(BranchName.from("trunk"))

  test("every create builder sets its own field and leaves every sibling alone"):
    val command = populated

    assertEquals(command.describedAs("replaced"), command.copy(description = Some("replaced")))
    assertEquals(command.asPrivate, command.copy(isPrivate = true))
    assertEquals(command.initialised, command.copy(autoInit = true))
    assertEquals(command.defaultingTo(Trunk), command.copy(defaultBranch = Some(Trunk)))
    assertEquals(command.withReadme("Default"), command.copy(readme = Some("Default")))
    assertEquals(command.withGitignores("Scala,Java"), command.copy(gitignores = Some("Scala,Java")))
    assertEquals(command.withLicense("MIT"), command.copy(license = Some("MIT")))
    assertEquals(command.withIssueLabels("Advanced"), command.copy(issueLabels = Some("Advanced")))
    assertEquals(command.asTemplate, command.copy(isTemplate = true))
    assertEquals(command.using(ObjectFormat.Sha256), command.copy(objectFormat = Some(ObjectFormat.Sha256)))
    assertEquals(command.trusting(TrustModel.Committer), command.copy(trustModel = Some(TrustModel.Committer)))

  test("the content templates do not turn on the initial commit they need to take effect"):
    val command = CreateRepository
      .named(repo("codeberg4s"))
      .withReadme("Default")
      .withGitignores("Scala")
      .withLicense("MIT")

    assertEquals(command.autoInit, false)
    assertEquals(command.readme, Some("Default"))

  test("naming a default branch does not turn on the initial commit that would create it either"):
    assertEquals(CreateRepository.named(repo("codeberg4s")).defaultingTo(Trunk).autoInit, false)

  test("marking a repository private does not also make it a template, and neither implies the other"):
    val hidden = CreateRepository.named(repo("codeberg4s")).asPrivate

    assertEquals(hidden.isPrivate, true)
    assertEquals(hidden.isTemplate, false)
    assertEquals(CreateRepository.named(repo("codeberg4s")).asTemplate.isPrivate, false)

  test("the object format and the trust model are separate choices, so one cannot be set through the other"):
    val command = CreateRepository.named(repo("codeberg4s")).using(ObjectFormat.Sha256)

    assertEquals(command.objectFormat, Some(ObjectFormat.Sha256))
    assertEquals(command.trustModel, None)
    assertEquals(CreateRepository.named(repo("codeberg4s")).trusting(TrustModel.Default).objectFormat, None)

  test("the name a command was started from survives every builder that follows it"):
    assertEquals(populated.describedAs("x").asPrivate.initialised.name.value, "codeberg4s")

  private def populated: CreateRepository =
    CreateRepository(
      name          = repo("codeberg4s"),
      description   = Some("original description"),
      isPrivate     = false,
      autoInit      = false,
      defaultBranch = Some(orFail(BranchName.from("main"))),
      readme        = Some("Original"),
      gitignores    = Some("Scala"),
      license       = Some("Apache-2.0"),
      issueLabels   = Some("Default"),
      isTemplate    = false,
      objectFormat  = Some(ObjectFormat.Sha1),
      trustModel    = Some(TrustModel.Default),
    )

  private def repo(value: String): RepoName = orFail(RepoName.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
