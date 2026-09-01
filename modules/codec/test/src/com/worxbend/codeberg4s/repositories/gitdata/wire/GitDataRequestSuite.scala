package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.gitdata.ApplyDiffPatch
import com.worxbend.codeberg4s.repositories.gitdata.CommitInclude
import com.worxbend.codeberg4s.repositories.gitdata.CommitStatusQuery
import com.worxbend.codeberg4s.repositories.gitdata.CommitStatusSort
import com.worxbend.codeberg4s.repositories.gitdata.CommitStatusState
import com.worxbend.codeberg4s.repositories.gitdata.GitAuthor
import com.worxbend.codeberg4s.repositories.gitdata.RefName

import munit.FunSuite

import java.time.Instant

/** What this group '''sends''': the two request bodies and every query string.
  *
  * Both bodies are derived from `spec/swagger.v1.json` — `NoteOptions` and `UpdateFileOptions` — since no capture of
  * either exists. The rule under test throughout is that an absent value produces an absent key or an absent parameter,
  * never an empty one: `"message": ""` suppresses Forgejo's generated commit message rather than accepting it, and
  * `state=` is a value the instance has to reject.
  */
final class GitDataRequestSuite extends FunSuite:

  // --- bodies ---------------------------------------------------------------

  test("a note body is the one key the model has"):
    assertEquals(NoteOptionsDto.render("reviewed"), """{"message":"reviewed"}""")

  test("an empty note is still sent, because blanking a note is a real instruction"):
    assertEquals(NoteOptionsDto.render(""), """{"message":""}""")

  test("a bare patch command sends the patch and nothing else"):
    assertEquals(DiffPatchOptionsDto.render(ApplyDiffPatch.of("--- a\n")), """{"content":"--- a\n"}""")

  test("the patch is sent verbatim, because this library refuses to guess at base64"):
    val rendered = DiffPatchOptionsDto.render(ApplyDiffPatch.of("diff --git a/x b/x"))

    assertEquals(rendered, """{"content":"diff --git a/x b/x"}""")

  test("only the keys the caller set appear, in the order the renderer fixes"):
    val command = ApplyDiffPatch
      .of("p")
      .withSha("cafebabe")
      .onBranch(branch("main"))
      .onNewBranch(branch("bot/patch"))
      .withMessage("apply")

    assertEquals(
      DiffPatchOptionsDto.render(command),
      """{"content":"p","sha":"cafebabe","branch":"main","new_branch":"bot/patch","message":"apply"}""",
    )

  test("an author is sent as Forgejo's Identity, name and email only"):
    val rendered = DiffPatchOptionsDto.render(ApplyDiffPatch.of("p").authoredBy(GitAuthor("Ada", "ada@example.org")))

    assertEquals(rendered, """{"content":"p","author":{"name":"Ada","email":"ada@example.org"}}""")

  test("both dates are sent as one nested object, in the RFC-3339 form Go parses"):
    val command = ApplyDiffPatch
      .of("p")
      .dated(Instant.parse("2026-08-01T10:00:00Z"), Instant.parse("2026-08-01T11:00:30Z"))

    assertEquals(
      DiffPatchOptionsDto.render(command),
      """{"content":"p","dates":{"author":"2026-08-01T10:00:00Z","committer":"2026-08-01T11:00:30Z"}}""",
    )

  test("the two booleans appear only when true, since false is what the instance already assumes"):
    assertEquals(
      DiffPatchOptionsDto.render(ApplyDiffPatch.of("p").signedOff.forcingNewBranch),
      """{"content":"p","signoff":true,"force_overwrite_new_branch":true}""",
    )

  // --- queries --------------------------------------------------------------

  test("the tree listing spells the size per_page, which no other route in the library does"):
    assertEquals(GitDataQueries.treeWindow(window(3, 50), false), List("page" -> "3", "per_page" -> "50"))

  test("a recursive tree listing says so before the window"):
    assertEquals(
      GitDataQueries.treeWindow(window(1, 30), true),
      List("recursive" -> "true", "page" -> "1", "per_page" -> "30"),
    )

  test("a commit selection that asserts nothing sends nothing"):
    assertEquals(GitDataQueries.commitInclude(CommitInclude.Default), Nil)

  test("declining all three parts sends all three as false"):
    assertEquals(
      GitDataQueries.commitInclude(CommitInclude.Minimal),
      List("stat" -> "false", "verification" -> "false", "files" -> "false"),
    )

  test("a partial selection sends only what was said"):
    assertEquals(GitDataQueries.commitInclude(CommitInclude.Default.withFiles(true)), List("files" -> "true"))

  test("the notes route has no stat parameter, so a selection carrying one drops it"):
    assertEquals(
      GitDataQueries.noteInclude(CommitInclude.Minimal),
      List("verification" -> "false", "files" -> "false"),
    )

  test("an empty status query filters nothing"):
    assertEquals(GitDataQueries.commitStatuses(CommitStatusQuery.Empty), Nil)

  test("a status query sends sort before state, in the order the spec declares them"):
    val query = CommitStatusQuery.Empty.sortedBy(CommitStatusSort.HighestIndex).inState(CommitStatusState.Error)

    assertEquals(GitDataQueries.commitStatuses(query), List("sort" -> "highestindex", "state" -> "error"))

  test("an absent ref means the default branch and sends no parameter at all"):
    assertEquals(GitDataQueries.atRef(None), Nil)

  test("a ref parameter carries its slashes verbatim, because the transport encodes a query value"):
    assertEquals(GitDataQueries.atRef(Some(ref("refs/heads/main"))), List("ref" -> "refs/heads/main"))

  // --- helpers --------------------------------------------------------------

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def branch(value: String): BranchName =
    orFail(BranchName.from(value))

  private def ref(value: String): RefName =
    orFail(RefName.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
