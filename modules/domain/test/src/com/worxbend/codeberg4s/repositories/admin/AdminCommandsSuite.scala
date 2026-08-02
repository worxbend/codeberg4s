package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.organizations.TeamId
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64

/** The commands this group builds, and the derived answers its read models offer.
  *
  * Every builder is a `copy` and would be dull to assert one by one; what is asserted here is the behaviour a reader
  * could get wrong — what a fresh command actually asks for, which defaults are deliberate, and the encode/decode
  * boundary of the two Base64 types.
  */
final class AdminCommandsSuite extends FunSuite:

  // --- repository lifecycle -------------------------------------------------

  test("a fresh create asks for a public, uninitialised, non-template repository"):
    val command = CreateRepository.named(repo("codeberg4s"))

    assertEquals(command.isPrivate, false)
    assertEquals(command.autoInit, false)
    assertEquals(command.isTemplate, false)
    assertEquals(command.objectFormat, None)

  test("initialised is the flag that makes the repository clonable, and it is not the default"):
    assertEquals(CreateRepository.named(repo("x")).initialised.autoInit, true)

  test("an empty edit changes nothing, and says so"):
    assertEquals(EditRepository.Empty.isEmpty, true)

  test("an edit that sets one field is no longer empty"):
    assertEquals(EditRepository.Empty.madePrivate.isEmpty, false)

  test("making a repository public is a set false, not an unset — the two are different requests"):
    assertEquals(EditRepository.Empty.madePublic.isPrivate, Some(false))
    assertEquals(EditRepository.Empty.isPrivate, None)

  test("archiving and un-archiving are the same field either way round"):
    assertEquals(EditRepository.Empty.archivedRepository.archived, Some(true))
    assertEquals(EditRepository.Empty.unarchivedRepository.archived, Some(false))

  test("a fresh migrate copies nothing but commits, which is Forgejo's own default"):
    val command = MigrateRepository.from("https://github.com/a/b.git", repo("b"))

    assertEquals(command.includesIssues, false)
    assertEquals(command.includesWiki, false)
    assertEquals(command.isMirror, false)

  test("withEverything sets the six content flags and leaves the mirror settings alone"):
    val command = MigrateRepository.from("https://github.com/a/b.git", repo("b")).withEverything

    assertEquals(command.includesIssues, true)
    assertEquals(command.includesLabels, true)
    assertEquals(command.includesMilestones, true)
    assertEquals(command.includesPullRequests, true)
    assertEquals(command.includesReleases, true)
    assertEquals(command.includesWiki, true)
    assertEquals(command.isMirror, false)

  test("a transfer names no team until one is granted, and keeps them in order"):
    val command = TransferRepository.to(owner("forgejo")).grantedTo(team(7L)).grantedTo(team(9L))

    assertEquals(TransferRepository.to(owner("forgejo")).teamIds, Vector.empty[TeamId])
    assertEquals(command.teamIds.map(_.value), Vector(7L, 9L))

  // --- branches -------------------------------------------------------------

  test("a fresh branch command branches from the repository default, which is an absent ref"):
    assertEquals(CreateBranch.named(branch("feature/x")).fromRef, None)

  test("startingAt accepts anything Forgejo resolves — a branch, a tag or a commit"):
    assertEquals(CreateBranch.named(branch("hotfix")).startingAt("v1.2.0").fromRef, Some("v1.2.0"))

  // --- contents -------------------------------------------------------------

  test("file bytes encode text as base64, so a caller cannot send raw text by mistake"):
    assertEquals(FileBytes.ofText("hello").base64, "aGVsbG8=")

  test("file bytes encode as UTF-8 before base64, so non-ASCII survives the round trip"):
    val bytes = FileBytes.ofText("café")

    assertEquals(bytes.decoded.map(decoded => String(decoded, StandardCharsets.UTF_8)), Some("café"))

  test("file bytes of an empty string are a legitimate empty file"):
    assertEquals(FileBytes.ofText("").base64, "")

  test("base64 a caller already produced is taken verbatim, trimmed of surrounding whitespace"):
    assertEquals(orFail(FileBytes.ofBase64("  aGVsbG8=  ")).base64, "aGVsbG8=")

  test("blank base64 is rejected, because it is always a bug rather than an empty file"):
    assertEquals(FileBytes.ofBase64("   ").swap.toOption.map(_.field), Some("fileContent"))

  test("a structurally impossible payload is accepted on the way in and reported as undecodable"):
    assertEquals(orFail(FileBytes.ofBase64("A")).decoded, None)

  test("the decoder ignores characters outside the alphabet rather than failing, as MIME Base64 does"):
    assert(orFail(FileBytes.ofBase64("not base64 at all!!")).decoded.isDefined, "the MIME decoder is lenient")

  test("encoded length counts the base64, not the file"):
    assertEquals(FileBytes.ofBytes(Array[Byte](1, 2, 3)).encodedLength, 4)

  test("an update carries the sha it expects to replace, because the type has no way not to"):
    val command = UpdateFile.of(FileBytes.ofText("x"), sha("abc123"))

    assertEquals(command.expectedSha.value, "abc123")
    assertEquals(command.fromPath, None)

  test("a batch cannot be empty, because the constructor demands the first operation"):
    val batch = ChangeFiles.of(FileOperation.Create(path("a.txt"), FileBytes.ofText("a")))

    assertEquals(batch.operations.length, 1)

  test("a batch keeps the caller's order, which is Forgejo's application order"):
    val batch = ChangeFiles
      .of(FileOperation.Create(path("a.txt"), FileBytes.ofText("a")))
      .and(FileOperation.Delete(path("b.txt"), sha("bbbb")))

    assertEquals(batch.operations.map(_.path.value), Vector("a.txt", "b.txt"))

  test("each operation derives its wire word from its case, so the two cannot disagree"):
    assertEquals(FileOperation.Create(path("a"), FileBytes.ofText("a")).wireValue, "create")
    assertEquals(FileOperation.Update(path("a"), FileBytes.ofText("a"), sha("aaaa"), None).wireValue, "update")
    assertEquals(FileOperation.Delete(path("a"), sha("aaaa")).wireValue, "delete")

  test("commit options default to the repository's own branch, message and author"):
    assertEquals(CommitOptions.Default.branch, None)
    assertEquals(CommitOptions.Default.message, None)
    assertEquals(CommitOptions.Default.signoff, false)
    assertEquals(CommitOptions.Default.forceOverwriteNewBranch, false)

  test("force-overwriting a new branch is opt-in, because a silent force-push is not a default"):
    assertEquals(CommitOptions.Default.overwritingNewBranch.forceOverwriteNewBranch, true)

  // --- avatar ---------------------------------------------------------------

  test("an avatar encodes its bytes, because the endpoint takes base64 in JSON and not a multipart part"):
    val encoded = JavaBase64.getEncoder.encodeToString(Array[Byte](0, 1, 2))

    assertEquals(orFail(AvatarImage.ofBytes(Array[Byte](0, 1, 2))).base64, encoded)

  test("an avatar of no bytes is rejected"):
    assertEquals(AvatarImage.ofBytes(Array.empty).swap.toOption.map(_.field), Some("avatarImage"))

  test("a blank avatar string is rejected"):
    assert(AvatarImage.ofBase64("  ").isLeft, "a blank avatar is not an image")

  test("base64 a caller already encoded is taken verbatim and only trimmed, never re-encoded"):
    val encoded = JavaBase64.getEncoder.encodeToString(Array[Byte](0, 1, 2))

    assertEquals(orFail(AvatarImage.ofBase64(encoded)).base64, encoded)
    assertEquals(orFail(AvatarImage.ofBase64(s"  $encoded\n")).base64, encoded)

  test("an avatar accepts text that is not valid base64, because Forgejo is the authority on the image"):
    assertEquals(orFail(AvatarImage.ofBase64("not base64 at all")).base64, "not base64 at all")

  // --- read models ----------------------------------------------------------

  test("a language breakdown totals every counted byte"):
    assertEquals(LanguageBreakdown(Map("Go" -> 100L, "Scala" -> 20L)).total, 120L)

  test("the dominant language is the biggest one"):
    assertEquals(LanguageBreakdown(Map("Go" -> 100L, "Scala" -> 20L)).dominant, Some("Go"))

  test("a tie between languages is broken by name, so the answer is stable across calls"):
    assertEquals(LanguageBreakdown(Map("Scala" -> 10L, "Go" -> 10L)).dominant, Some("Go"))

  test("an unanalysed repository has no dominant language and no bytes"):
    assertEquals(LanguageBreakdown.Empty.dominant, None)
    assertEquals(LanguageBreakdown.Empty.total, 0L)
    assertEquals(LanguageBreakdown.Empty.isEmpty, true)

  test("a subscription notifies only when it is subscribed and not muted"):
    assertEquals(watch(subscribed = true, ignored = false).isNotifying, true)
    assertEquals(watch(subscribed = true, ignored = true).isNotifying, false)
    assertEquals(watch(subscribed = false, ignored = false).isNotifying, false)

  test("a fork is behind only when syncing is permitted and there is something to sync"):
    assertEquals(ForkSyncInfo(allowed = true, commitsBehind = 3L, None, None).isBehind, true)
    assertEquals(ForkSyncInfo(allowed = true, commitsBehind = 0L, None, None).isBehind, false)
    assertEquals(ForkSyncInfo(allowed = false, commitsBehind = 3L, None, None).isBehind, false)

  test("a push mirror is failing exactly when it reported an error"):
    assertEquals(mirror(Some("dial tcp: timeout")).isFailing, true)
    assertEquals(mirror(None).isFailing, false)

  private def watch(subscribed: Boolean, ignored: Boolean): WatchStatus =
    WatchStatus(subscribed = subscribed, ignored = ignored, None, None, None, None)

  private def mirror(lastError: Option[String]): PushMirror =
    PushMirror(
      remoteName    = orFail(MirrorName.from("remote_x")),
      remoteAddress = None,
      repoName      = None,
      branchFilter  = None,
      interval      = None,
      syncsOnCommit = false,
      lastError     = lastError,
      publicKey     = None,
      createdAt     = None,
      lastUpdateAt  = None,
    )

  private def repo(value: String): RepoName = orFail(RepoName.from(value))

  private def owner(value: String): Owner = orFail(Owner.from(value))

  private def branch(value: String): BranchName = orFail(BranchName.from(value))

  private def path(value: String): ContentPath = orFail(ContentPath.from(value))

  private def sha(value: String): CommitSha = orFail(CommitSha.from(value))

  private def team(value: Long): TeamId = orFail(TeamId.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
