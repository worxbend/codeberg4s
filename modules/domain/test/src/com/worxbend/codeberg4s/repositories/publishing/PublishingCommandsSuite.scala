package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.TagName

import munit.FunSuite

/** The command types of the publishing group: what each builder sets, and what "unset" means for each of them.
  *
  * The distinction under test throughout is between a field left alone and a field explicitly set to a falsy value. The
  * renderers in `modules/codec` turn that distinction into the presence or absence of a JSON key, and getting it wrong
  * is how a `PATCH` silently publishes a draft.
  */
final class PublishingCommandsSuite extends FunSuite:

  private val Tag: TagName = orFail(TagName.from("v16.0.2"))

  private val Slashed: TagName = orFail(TagName.from("v16.0/forgejo"))

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  // --- CreateRelease --------------------------------------------------------

  test("a create-release command starts with nothing but the tag, and all three flags false"):
    val command = CreateRelease.of(Tag)

    assertEquals(command.tagName.value, "v16.0.2")
    assertEquals(command.target, None)
    assertEquals(command.name, None)
    assertEquals(command.body, None)
    assertEquals(command.isDraft, false)
    assertEquals(command.isPrerelease, false)
    assertEquals(command.hidesArchiveLinks, false)

  test("a create-release command carries a multi-segment tag unchanged"):
    assertEquals(CreateRelease.of(Slashed).tagName.value, "v16.0/forgejo")

  test("draft and prerelease are independent, because Forgejo allows a prerelease draft"):
    val command = CreateRelease.of(Tag).asDraft.asPrerelease

    assertEquals((command.isDraft, command.isPrerelease), (true, true))

  test("the create-release builders set what they name and nothing else"):
    val command = CreateRelease.of(Tag).onTarget("main").titled("v16.0.2").withNotes("# notes").hidingArchiveLinks

    assertEquals(command.target, Some("main"))
    assertEquals(command.name, Some("v16.0.2"))
    assertEquals(command.body, Some("# notes"))
    assertEquals(command.hidesArchiveLinks, true)
    assertEquals(command.isDraft, false)

  // --- EditRelease ----------------------------------------------------------

  test("an empty edit-release command mentions nothing"):
    assertEquals(EditRelease.Empty.isEmpty, true)

  test("publishing a draft is a stated false, not an omission — the type has to tell them apart"):
    assertEquals(EditRelease.Empty.isDraft, None)
    assertEquals(EditRelease.Empty.draft(false).isDraft, Some(false))
    assertEquals(EditRelease.Empty.draft(false).isEmpty, false)

  test("the edit-release builders set what they name and nothing else"):
    val command = EditRelease.Empty.retaggedTo(Slashed).titled("v16.0.2").prerelease(true).archiveLinksHidden(false)

    assertEquals(command.tagName.map(_.value), Some("v16.0/forgejo"))
    assertEquals(command.name, Some("v16.0.2"))
    assertEquals(command.isPrerelease, Some(true))
    assertEquals(command.hidesArchiveLinks, Some(false))
    assertEquals(command.body, None)
    assertEquals(command.isDraft, None)

  test("an edit-release command can also set the target and the notes"):
    val command = EditRelease.Empty.onTarget("v16.0/forgejo").withNotes("rewritten")

    assertEquals((command.target, command.body), (Some("v16.0/forgejo"), Some("rewritten")))

  // --- CreateTag ------------------------------------------------------------

  test("a tag with no message is lightweight, which is what an absent message means"):
    val command = CreateTag.of(Tag)

    assertEquals(command.message, None)
    assertEquals(command.target, None)

  test("annotating a tag is the only way to make it annotated"):
    val command = CreateTag.of(Tag).annotated("security patches").at("main")

    assertEquals(command.message, Some("security patches"))
    assertEquals(command.target, Some("main"))

  // --- EditAsset ------------------------------------------------------------

  test("an empty edit-asset command mentions nothing"):
    assertEquals(EditAsset.Empty.isEmpty, true)

  test("a rename does not offer to set the external download URL"):
    val command = EditAsset.Empty.renamedTo("forgejo-16.0.2-linux-amd64")

    assertEquals(command.name, Some("forgejo-16.0.2-linux-amd64"))
    assertEquals(command.browserDownloadUrl, None)

  test("the external download URL can be set on its own"):
    assertEquals(
      EditAsset.Empty.pointingAt("https://mirror.example/f").browserDownloadUrl,
      Some("https://mirror.example/f"),
    )

  // --- CreateFork -----------------------------------------------------------

  test("the plain fork mentions neither a name nor an organisation"):
    assertEquals(CreateFork.Empty.isEmpty, true)

  test("a fork can be renamed and redirected independently"):
    val command = CreateFork.Empty.named(Name).into(Handle)

    assertEquals(command.name.map(_.value), Some("forgejo"))
    assertEquals(command.organization.map(_.value), Some("forgejo"))
    assertEquals(command.isEmpty, false)

  // --- GenerateRepository ---------------------------------------------------

  test("a generate command starts with the two required fields and copies nothing"):
    val command = GenerateRepository.of(Handle, Name)

    assertEquals(command.owner.value, "forgejo")
    assertEquals(command.name.value, "forgejo")
    assertEquals(command.includesGitContent, false)
    assertEquals(command.isPrivate, false)
    assertEquals(command.description, None)
    assertEquals(command.defaultBranch, None)

  test("asking for git content is a separate decision from the rest, because it is the one that surprises"):
    assertEquals(GenerateRepository.of(Handle, Name).withGitContent.includesGitContent, true)

  test("everything sets all seven include-flags and leaves privacy and description alone"):
    val command = GenerateRepository.of(Handle, Name).everything

    assertEquals(
      List(
        command.includesAvatar,
        command.includesGitContent,
        command.includesGitHooks,
        command.includesLabels,
        command.includesProtectedBranches,
        command.includesTopics,
        command.includesWebhooks,
      ),
      List.fill(7)(true),
    )
    assertEquals(command.isPrivate, false)
    assertEquals(command.description, None)

  test("the remaining generate builders set what they name"):
    val branch  = orFail(BranchName.from("v16.0/forgejo"))
    val command = GenerateRepository.of(Handle, Name).describedAs("a fork").defaultingTo(branch).asPrivate

    assertEquals(command.description, Some("a fork"))
    assertEquals(command.defaultBranch.map(_.value), Some("v16.0/forgejo"))
    assertEquals(command.isPrivate, true)

  test("the individual include-flags are individually settable"):
    val command = GenerateRepository
      .of(Handle, Name)
      .withAvatar
      .withGitHooks
      .withLabels
      .withProtectedBranches
      .withTopics
      .withWebhooks

    assertEquals(command.includesGitContent, false)
    assertEquals(command.includesAvatar, true)
    assertEquals(command.includesGitHooks, true)
    assertEquals(command.includesLabels, true)
    assertEquals(command.includesProtectedBranches, true)
    assertEquals(command.includesTopics, true)
    assertEquals(command.includesWebhooks, true)

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
