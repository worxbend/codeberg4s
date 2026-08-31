package com.worxbend.codeberg4s.repositories.publishing.wire

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.TagName
import com.worxbend.codeberg4s.repositories.publishing.CreateFork
import com.worxbend.codeberg4s.repositories.publishing.CreateRelease
import com.worxbend.codeberg4s.repositories.publishing.CreateTag
import com.worxbend.codeberg4s.repositories.publishing.EditAsset
import com.worxbend.codeberg4s.repositories.publishing.EditRelease
import com.worxbend.codeberg4s.repositories.publishing.GenerateRepository
import com.worxbend.codeberg4s.repositories.publishing.Topic

import munit.FunSuite

/** What each publishing request renders to, key by key.
  *
  * Every model asserted here is derived from `spec/swagger.v1.json` rather than from a capture: the golden fixtures are
  * responses, and the anonymous harvest could not record a request. The one exception is the `topics` key, which
  * `golden/repository/topics.json` confirms on the way back.
  *
  * The assertions are on the exact JSON text, not on a re-parsed object, because the thing that goes wrong is a key
  * that should not have been emitted at all — and a re-parsed comparison against an expected object hides that.
  */
final class PublishingRequestsSuite extends FunSuite:

  private val Tag: TagName = orFail(TagName.from("v16.0.2"))

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  // --- CreateReleaseOption --------------------------------------------------

  test("a minimal create-release sends the tag and nothing else"):
    assertEquals(CreateReleaseOptionDto.render(CreateRelease.of(Tag)), """{"tag_name":"v16.0.2"}""")

  test("a create-release emits a flag only when it is true, because false is what Forgejo assumes"):
    assertEquals(
      CreateReleaseOptionDto.render(CreateRelease.of(Tag).asDraft),
      """{"tag_name":"v16.0.2","draft":true}""",
    )

  test("a full create-release emits every key in spec order"):
    val command = CreateRelease
      .of(Tag)
      .onTarget("main")
      .titled("v16.0.2")
      .withNotes("# notes")
      .asDraft
      .asPrerelease
      .hidingArchiveLinks

    assertEquals(
      CreateReleaseOptionDto.render(command),
      """{"tag_name":"v16.0.2","target_commitish":"main","name":"v16.0.2","body":"# notes",""" +
        """"draft":true,"prerelease":true,"hide_archive_links":true}""",
    )

  // --- EditReleaseOption ----------------------------------------------------

  test("an empty edit-release renders as an object that changes nothing"):
    assertEquals(EditReleaseOptionDto.render(EditRelease.Empty), "{}")

  test("publishing a draft is a false that must survive rendering"):
    assertEquals(EditReleaseOptionDto.render(EditRelease.Empty.draft(false)), """{"draft":false}""")

  test("an edit-release emits only the keys the caller mentioned"):
    val command = EditRelease.Empty.retaggedTo(Tag).withNotes("rewritten").archiveLinksHidden(true)

    assertEquals(
      EditReleaseOptionDto.render(command),
      """{"tag_name":"v16.0.2","body":"rewritten","hide_archive_links":true}""",
    )

  test("an edit-release can also state the target, the title and the prerelease flag"):
    val command = EditRelease.Empty.onTarget("main").titled("v16.0.2").prerelease(false)

    assertEquals(
      EditReleaseOptionDto.render(command),
      """{"target_commitish":"main","name":"v16.0.2","prerelease":false}""",
    )

  // --- CreateTagOption ------------------------------------------------------

  test("a lightweight tag sends no message key at all"):
    assertEquals(CreateTagOptionDto.render(CreateTag.of(Tag)), """{"tag_name":"v16.0.2"}""")

  test("an annotated tag sends the message that makes it annotated"):
    assertEquals(
      CreateTagOptionDto.render(CreateTag.of(Tag).annotated("security patches").at("main")),
      """{"tag_name":"v16.0.2","message":"security patches","target":"main"}""",
    )

  test("a multi-segment tag name is sent whole in the body, slashes and all"):
    val slashed = orFail(TagName.from("v16.0/forgejo"))

    assertEquals(CreateTagOptionDto.render(CreateTag.of(slashed)), """{"tag_name":"v16.0/forgejo"}""")

  // --- EditAttachmentOptions ------------------------------------------------

  test("an empty attachment edit renders as an object that changes nothing"):
    assertEquals(EditAttachmentOptionsDto.render(EditAsset.Empty), "{}")

  test("a rename does not smuggle in a browser_download_url the endpoint would reject"):
    assertEquals(
      EditAttachmentOptionsDto.render(EditAsset.Empty.renamedTo("checksums.txt")),
      """{"name":"checksums.txt"}""",
    )

  test("both attachment keys can be sent together"):
    val command = EditAsset.Empty.renamedTo("checksums.txt").pointingAt("https://mirror.example/f")

    assertEquals(
      EditAttachmentOptionsDto.render(command),
      """{"name":"checksums.txt","browser_download_url":"https://mirror.example/f"}""",
    )

  // --- RepoTopicOptions -----------------------------------------------------

  test("a topic replacement sends the key the golden capture confirms"):
    val topics = Vector("forge", "forgejo", "git", "self-hosted").map(name => orFail(Topic.from(name)))

    assertEquals(
      RepoTopicOptionsDto.render(topics),
      """{"topics":["forge","forgejo","git","self-hosted"]}""",
    )

  test("clearing every topic is an empty array, not an omitted key"):
    assertEquals(RepoTopicOptionsDto.render(Vector.empty), """{"topics":[]}""")

  // --- CreateForkOption -----------------------------------------------------

  test("the plain fork sends an empty object"):
    assertEquals(CreateForkOptionDto.render(CreateFork.Empty), "{}")

  test("a fork states only the parts of its destination the caller chose"):
    assertEquals(CreateForkOptionDto.render(CreateFork.Empty.into(Handle)), """{"organization":"forgejo"}""")
    assertEquals(
      CreateForkOptionDto.render(CreateFork.Empty.named(Name).into(Handle)),
      """{"name":"forgejo","organization":"forgejo"}""",
    )

  // --- GenerateRepoOption ---------------------------------------------------

  test("a minimal generate sends the two keys the spec marks required and copies nothing"):
    assertEquals(
      GenerateRepoOptionDto.render(GenerateRepository.of(Handle, Name)),
      """{"owner":"forgejo","name":"forgejo"}""",
    )

  test("a generate that asks for everything emits all eight booleans"):
    assertEquals(
      GenerateRepoOptionDto.render(GenerateRepository.of(Handle, Name).everything.asPrivate),
      """{"owner":"forgejo","name":"forgejo","private":true,"avatar":true,"git_content":true,""" +
        """"git_hooks":true,"labels":true,"protected_branch":true,"topics":true,"webhooks":true}""",
    )

  test("a generate emits the description and default branch when they are set"):
    val branch  = orFail(BranchName.from("v16.0/forgejo"))
    val command = GenerateRepository.of(Handle, Name).describedAs("a fork").defaultingTo(branch).withGitContent

    assertEquals(
      GenerateRepoOptionDto.render(command),
      """{"owner":"forgejo","name":"forgejo","description":"a fork","default_branch":"v16.0/forgejo",""" +
        """"git_content":true}""",
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
