package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.LabelId
import com.worxbend.codeberg4s.issues.MilestoneId
import com.worxbend.codeberg4s.pulls.CreatePullRequest
import com.worxbend.codeberg4s.pulls.EditPullRequest
import com.worxbend.codeberg4s.pulls.MergePullRequest
import com.worxbend.codeberg4s.pulls.MergeStyle
import com.worxbend.codeberg4s.pulls.PullRequestHead
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.Owner

import munit.FunSuite

import java.time.Instant

/** The three request bodies this group sends.
  *
  * Asserted as rendered JSON text rather than through a stub backend, because the interesting decision in every one of
  * them is '''which keys appear at all''' — and for the merge body, how they are spelled.
  */
final class PullRequestBodySuite extends FunSuite:

  private val Base: BranchName = orFail(BranchName.from("forgejo"))

  private val Topic: BranchName = orFail(BranchName.from("fix-pep691"))

  private val Fork: Owner = orFail(Owner.from("trim21"))

  private val Head: CommitSha = orFail(CommitSha.from("0fc17509190ed028a74eafbcf095917715aec3a1"))

  // --- create ---------------------------------------------------------------

  test("a minimal create sends the three things a pull request cannot be opened without, and nothing else"):
    assertEquals(
      CreatePullRequestOptionDto.render(create),
      """{"title":"fix the hook quoting","head":"fix-pep691","base":"forgejo"}""",
    )

  test("a cross-repository head is rendered as Forgejo's owner:branch"):
    val command = orFail(CreatePullRequest.of("t", PullRequestHead.crossRepository(Fork, Topic), Base))

    assertEquals(
      CreatePullRequestOptionDto.render(command),
      """{"title":"t","head":"trim21:fix-pep691","base":"forgejo"}""",
    )

  test("labels are sent as ids, and only when the caller attached some"):
    val command = create.labelled(Vector(orFail(LabelId.from(201023L)), orFail(LabelId.from(201030L))))

    assert(
      CreatePullRequestOptionDto.render(command).contains(""""labels":[201023,201030]"""),
      CreatePullRequestOptionDto.render(command),
    )

  test("an empty assignee vector is not a statement, so it contributes no key"):
    assertEquals(
      CreatePullRequestOptionDto.render(create.assignedTo(Vector.empty)),
      CreatePullRequestOptionDto.render(create),
    )

  test("a deadline is rendered in the RFC-3339 form Go parses"):
    val command = create.dueBy(Instant.parse("2026-09-01T12:30:00Z"))

    assert(
      CreatePullRequestOptionDto.render(command).contains(""""due_date":"2026-09-01T12:30:00Z""""),
      CreatePullRequestOptionDto.render(command),
    )

  // --- edit -----------------------------------------------------------------

  test("an empty edit renders as {}, a well-formed request that changes nothing"):
    assertEquals(EditPullRequestOptionDto.render(EditPullRequest.Empty), "{}")

  test("closing a pull request sends the state transition and nothing else"):
    assertEquals(EditPullRequestOptionDto.render(EditPullRequest.Empty.close), """{"state":"closed"}""")

  test("clearing assignees sends an explicit empty array, because Forgejo replaces rather than adds"):
    assertEquals(
      EditPullRequestOptionDto.render(EditPullRequest.Empty.assignedTo(Vector.empty)),
      """{"assignees":[]}""",
    )

  test("clearing labels does the same, for the same reason"):
    assertEquals(EditPullRequestOptionDto.render(EditPullRequest.Empty.labelled(Vector.empty)), """{"labels":[]}""")

  test("clearing a deadline is its own flag, not a null due date"):
    assertEquals(
      EditPullRequestOptionDto.render(EditPullRequest.Empty.withoutDueDate),
      """{"unset_due_date":true}""",
    )

  test("switching maintainer edit off is a request, so false is emitted rather than omitted"):
    assertEquals(
      EditPullRequestOptionDto.render(EditPullRequest.Empty.forbiddingMaintainerEdit),
      """{"allow_maintainer_edit":false}""",
    )

  test("retargeting sends the new base branch"):
    assertEquals(EditPullRequestOptionDto.render(EditPullRequest.Empty.withBase(Base)), """{"base":"forgejo"}""")

  test("a milestone is sent as its id"):
    assertEquals(
      EditPullRequestOptionDto.render(EditPullRequest.Empty.inMilestone(orFail(MilestoneId.from(137464L)))),
      """{"milestone":137464}""",
    )

  // --- merge ----------------------------------------------------------------

  test("a minimal merge sends only the required style, under Forgejo's capitalised key"):
    assertEquals(MergePullRequestOptionDto.render(MergePullRequest.using(MergeStyle.Squash)), """{"Do":"squash"}""")

  test("every merge style has its own wire spelling, and none of them is guessed"):
    assertEquals(
      MergeStyle.values.toVector.map(_.wireValue),
      Vector("merge", "rebase", "rebase-merge", "squash", "fast-forward-only", "manually-merged"),
    )

  test("the title and message keys keep Forgejo's struct-field spelling rather than snake_case"):
    val command = MergePullRequest.using(MergeStyle.Merge).withTitle("Merge 13726").withMessage("Reviewed-by: …")

    assertEquals(
      MergePullRequestOptionDto.render(command),
      """{"Do":"merge","MergeTitleField":"Merge 13726","MergeMessageField":"Reviewed-by: …"}""",
    )

  test("the head guard is sent as head_commit_id, which is what makes a repeat safe"):
    assertEquals(
      MergePullRequestOptionDto.render(MergePullRequest.using(MergeStyle.Merge).expecting(Head)),
      s"""{"Do":"merge","head_commit_id":"${Head.value}"}""",
    )

  test("a manually recorded merge names the commit under MergeCommitID"):
    val command = MergePullRequest.using(MergeStyle.ManuallyMerged).recordingMerge(Head)

    assertEquals(
      MergePullRequestOptionDto.render(command),
      s"""{"Do":"manually-merged","MergeCommitID":"${Head.value}"}""",
    )

  test("the three flags are emitted only when the caller asked for them"):
    val command = MergePullRequest.using(MergeStyle.Rebase).deletingSourceBranch.forcing.whenChecksSucceed

    assertEquals(
      MergePullRequestOptionDto.render(command),
      """{"Do":"rebase","delete_branch_after_merge":true,"force_merge":true,"merge_when_checks_succeed":true}""",
    )

  test("not asking to delete the branch sends no key at all, rather than an explicit false"):
    assert(
      !MergePullRequestOptionDto.render(MergePullRequest.using(MergeStyle.Merge)).contains("delete_branch_after_merge"),
      "an unset flag must not become a key",
    )

  private def create: CreatePullRequest =
    orFail(CreatePullRequest.of("fix the hook quoting", PullRequestHead.branch(Topic), Base))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
