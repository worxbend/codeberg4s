package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.Commit
import com.worxbend.codeberg4s.repositories.CommitSha

import munit.FunSuite

import java.time.Instant

/** The request-shaped and derived-value types of the raw-git group.
  *
  * The common thread: each one has a state that means "the caller said nothing", and each has to keep that distinct
  * from a state that means "the caller said no". Collapsing the two is how a library starts asserting things on a
  * caller's behalf.
  */
final class GitDataCommandsSuite extends FunSuite:

  test("the default commit selection asserts nothing, so the instance's own defaults apply"):
    assertEquals(CommitInclude.Default, CommitInclude(None, None, None))

  test("the minimal commit selection declines all three parts explicitly"):
    assertEquals(CommitInclude.Minimal, CommitInclude(Some(false), Some(false), Some(false)))

  test("each part is set independently and leaves the others unsaid"):
    val chosen = CommitInclude.Default.withStat(true).withFiles(false)

    assertEquals(chosen.stat, Some(true))
    assertEquals(chosen.files, Some(false))
    assertEquals(chosen.verification, None)

  test("verification is settable on its own"):
    assertEquals(CommitInclude.Default.withVerification(false).verification, Some(false))

  test("an empty status query filters nothing"):
    assertEquals(CommitStatusQuery.Empty, CommitStatusQuery(None, None))

  test("a status query narrows by sort and state independently"):
    val query = CommitStatusQuery.Empty.sortedBy(CommitStatusSort.Oldest).inState(CommitStatusState.Failure)

    assertEquals(query.sort, Some(CommitStatusSort.Oldest))
    assertEquals(query.state, Some(CommitStatusState.Failure))

  test("a fresh patch command sets nothing but the patch"):
    val command = ApplyDiffPatch.of("--- a\n+++ b\n")

    assertEquals(command.content, "--- a\n+++ b\n")
    assertEquals(command.branch, None)
    assertEquals(command.message, None)
    assertEquals(command.signoff, false)
    assertEquals(command.forceOverwriteNewBranch, false)

  test("each narrowing of a patch command leaves the rest alone"):
    val command = ApplyDiffPatch
      .of("patch")
      .onBranch(branch("main"))
      .onNewBranch(branch("bot/patch"))
      .withMessage("apply upstream fix")
      .withSha("cafebabe")
      .signedOff
      .forcingNewBranch

    assertEquals(command.branch.map(_.value), Some("main"))
    assertEquals(command.newBranch.map(_.value), Some("bot/patch"))
    assertEquals(command.message, Some("apply upstream fix"))
    assertEquals(command.sha, Some("cafebabe"))
    assertEquals(command.signoff, true)
    assertEquals(command.forceOverwriteNewBranch, true)

  test("authorship and dates are set as pairs, because Forgejo takes them as one object"):
    val authored  = Instant.parse("2026-08-01T10:00:00Z")
    val committed = Instant.parse("2026-08-01T11:00:00Z")
    val command   = ApplyDiffPatch
      .of("patch")
      .authoredBy(GitAuthor("Ada", "ada@example.org"))
      .committedBy(GitAuthor("Bob", "bob@example.org"))
      .dated(authored, committed)

    assertEquals(command.author, Some(GitAuthor("Ada", "ada@example.org")))
    assertEquals(command.committer, Some(GitAuthor("Bob", "bob@example.org")))
    assertEquals(command.authorDate, Some(authored))
    assertEquals(command.committerDate, Some(committed))

  test("a comparison that returned every commit it counted is not truncated"):
    assertEquals(comparison(2L, 2).isTruncated, false)

  test("a comparison that returned fewer commits than it counted is truncated, which no header says"):
    assertEquals(comparison(400L, 50).isTruncated, true)

  test("a comparison of a ref with itself is empty and untruncated"):
    assertEquals(comparison(0L, 0).isTruncated, false)

  test("editorconfig properties are looked up case-insensitively, as the format specifies"):
    val definitions = EditorConfigDefinitions(Map("indent_style" -> "space", "indent_size" -> "4"))

    assertEquals(definitions.valueOf("INDENT_STYLE"), Some("space"))
    assertEquals(definitions.valueOf("  indent_size "), Some("4"))
    assertEquals(definitions.valueOf("charset"), None)
    assertEquals(definitions.isEmpty, false)

  test("a path with no editorconfig properties is empty rather than an error"):
    assertEquals(EditorConfigDefinitions(Map.empty).isEmpty, true)

  private def comparison(total: Long, returned: Int): CommitComparison =
    CommitComparison(total, Vector.fill(returned)(commit), Vector.empty)

  private val commit: Commit =
    Commit(sha(), None, None, None, None, None, None, Vector.empty, Vector.empty, None)

  private def sha(): CommitSha =
    orFail(CommitSha.from("cafebabe"), "commitSha")

  private def branch(value: String): BranchName =
    orFail(BranchName.from(value), "branch")

  private def orFail[A](result: Either[ValidationError, A], what: String): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid $what fixture: ${error.field} ${error.message}")
