package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.ContentPath

import munit.FunSuite

import java.time.Instant

/** The four contents-write commands and the commit settings they all carry.
  *
  * Two properties are asserted, and both are about what a builder must '''not''' do. The first is the usual one: every
  * builder is applied to a value with every field already set, so one that reset a sibling fails here. The second is
  * specific to this group — replacing the commit settings must not disturb the optimistic-concurrency sha, because a
  * write that lost its guard is a write that silently overwrites whatever landed in between.
  */
final class FileWriteCommandSuite extends FunSuite:

  private val Main: BranchName = orFail(BranchName.from("main"))

  private val Topic: BranchName = orFail(BranchName.from("topic/rewrite"))

  private val Author: CommitIdentity = CommitIdentity(Some("Ada"), Some("ada@example.test"))

  private val Committer: CommitIdentity = CommitIdentity(Some("Grace"), Some("grace@example.test"))

  private val Dates: CommitDates =
    CommitDates(author = Some(Instant.parse("2026-08-01T09:00:00Z")), committer = Some(Instant.EPOCH))

  test("every commit-option builder sets its own field and leaves every sibling alone"):
    val options = populatedOptions

    assertEquals(options.on(Topic), options.copy(branch = Some(Topic)))
    assertEquals(options.onNewBranch(Topic), options.copy(newBranch = Some(Topic)))
    assertEquals(options.describedAs("replaced"), options.copy(message = Some("replaced")))
    assertEquals(options.authoredBy(Committer), options.copy(author = Some(Committer)))
    assertEquals(options.committedBy(Author), options.copy(committer = Some(Author)))
    assertEquals(options.dated(CommitDates.Unset), options.copy(dates = CommitDates.Unset))
    assertEquals(options.signedOff, options.copy(signoff = true))
    assertEquals(options.overwritingNewBranch, options.copy(forceOverwriteNewBranch = true))

  test("branching off a base keeps the base, because the two together are what say where to branch from"):
    val options = CommitOptions.Default.on(Main).onNewBranch(Topic)

    assertEquals(options.branch, Some(Main))
    assertEquals(options.newBranch, Some(Topic))

  test("the author and the committer are separate identities, so recording one does not record the other"):
    assertEquals(CommitOptions.Default.authoredBy(Author).committer, None)
    assertEquals(CommitOptions.Default.committedBy(Committer).author, None)

  test("signing off does not force-push, and force-pushing does not sign off"):
    assertEquals(CommitOptions.Default.signedOff.forceOverwriteNewBranch, false)
    assertEquals(CommitOptions.Default.overwritingNewBranch.signoff, false)

  test("dating a commit explicitly replaces both dates at once, which is what the wire model carries"):
    val dated = CommitOptions.Default.dated(Dates)

    assertEquals(dated.dates.author, Some(Instant.parse("2026-08-01T09:00:00Z")))
    assertEquals(dated.dates.committer, Some(Instant.EPOCH))
    assertEquals(CommitOptions.Default.dates, CommitDates.Unset)

  // --- the commands ---------------------------------------------------------

  test("a create command carries the bytes it was given and commits with Forgejo's own defaults"):
    val command = CreateFile.of(FileBytes.ofText("hello"))

    assertEquals(command.content.base64, "aGVsbG8=")
    assertEquals(command.commit, CommitOptions.Default)

  test("a delete command carries the sha guard it was given and nothing it was not"):
    val command = DeleteFile.of(sha("abc123"))

    assertEquals(command.expectedSha.value, "abc123")
    assertEquals(command.commit, CommitOptions.Default)

  test("replacing the commit settings of an update leaves the sha guard and the bytes alone"):
    val command     = UpdateFile.of(FileBytes.ofText("x"), sha("abc123")).movedFrom(path("old/x.txt"))
    val recommitted = command.committing(populatedOptions)

    assertEquals(recommitted, command.copy(commit = populatedOptions))
    assertEquals(recommitted.expectedSha.value, "abc123")
    assertEquals(recommitted.content.base64, FileBytes.ofText("x").base64)
    assertEquals(recommitted.fromPath.map(_.value), Some("old/x.txt"))

  test("replacing the commit settings of a delete leaves its sha guard alone"):
    val command = DeleteFile.of(sha("abc123")).committing(populatedOptions)

    assertEquals(command.expectedSha.value, "abc123")
    assertEquals(command.commit.message, Some("original message"))

  test("replacing the commit settings of a create leaves the bytes alone"):
    val command = CreateFile.of(FileBytes.ofText("hello")).committing(populatedOptions)

    assertEquals(command.content.base64, "aGVsbG8=")
    assertEquals(command.commit, populatedOptions)

  test("replacing the commit settings of a batch leaves every operation in it, in order"):
    val batch       = ChangeFiles
      .of(FileOperation.Create(path("a.txt"), FileBytes.ofText("a")))
      .and(FileOperation.Delete(path("b.txt"), sha("bbbb")))
    val recommitted = batch.committing(populatedOptions)

    assertEquals(recommitted, batch.copy(commit = populatedOptions))
    assertEquals(recommitted.operations.map(_.path.value), Vector("a.txt", "b.txt"))

  test("moving a file records the source and leaves the sha guard on it"):
    val command = UpdateFile.of(FileBytes.ofText("x"), sha("abc123"))

    assertEquals(command.movedFrom(path("old/x.txt")), command.copy(fromPath = Some(path("old/x.txt"))))
    assertEquals(command.fromPath, None)

  private def populatedOptions: CommitOptions =
    CommitOptions(
      branch                  = Some(Main),
      newBranch               = Some(orFail(BranchName.from("original/branch"))),
      message                 = Some("original message"),
      author                  = Some(Author),
      committer               = Some(Committer),
      dates                   = Dates,
      signoff                 = false,
      forceOverwriteNewBranch = false,
    )

  private def path(value: String): ContentPath = orFail(ContentPath.from(value))

  private def sha(value: String): CommitSha = orFail(CommitSha.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
