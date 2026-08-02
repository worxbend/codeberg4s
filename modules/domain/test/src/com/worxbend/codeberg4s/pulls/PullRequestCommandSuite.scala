package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.IssueStateChange
import com.worxbend.codeberg4s.issues.LabelId
import com.worxbend.codeberg4s.issues.MilestoneId
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha

import munit.FunSuite

import java.time.Instant

/** The three pull-request command types: what their constructors insist on, and what their builders leave alone.
  *
  * Each builder is applied to an instance with '''every''' field already set, so a builder that dropped a sibling — the
  * classic copy-shaped defect — fails here rather than silently sending a different request. For [[MergePullRequest]]
  * that matters more than usual: dropping `head_commit_id` turns a safe retry into a merge of whatever the branch has
  * since become.
  */
final class PullRequestCommandSuite extends FunSuite:

  private val Friday: Instant = Instant.parse("2026-08-07T17:00:00Z")

  private val Monday: Instant = Instant.parse("2026-08-10T09:00:00Z")

  private val Bug: LabelId = orFail(LabelId.from(102L))

  private val Chore: LabelId = orFail(LabelId.from(103L))

  private val Release: MilestoneId = orFail(MilestoneId.from(3109L))

  private val Next: MilestoneId = orFail(MilestoneId.from(3110L))

  private val Main: BranchName = orFail(BranchName.from("main"))

  private val Stable: BranchName = orFail(BranchName.from("v1/stable"))

  private val Head: CommitSha = orFail(CommitSha.from("1bdb1938c1a2b1e9f0d3"))

  private val Landed: CommitSha = orFail(CommitSha.from("f00dcafe4242bead"))

  // -- CreatePullRequest ----------------------------------------------------

  test("a pull request command starts from a title, a head and a base, and nothing else"):
    val command = orFail(CreatePullRequest.of("fix the hook quoting", PullRequestHead.branch(Stable), Main))

    assertEquals(command.title, "fix the hook quoting")
    assertEquals(command.head.value, "v1/stable")
    assertEquals(command.base, Main)
    assertEquals(command.body, None)
    assertEquals(command.assignees, Vector.empty[String])
    assertEquals(command.labels, Vector.empty[LabelId])
    assertEquals(command.milestone, None)
    assertEquals(command.dueDate, None)

  test("a title is trimmed"):
    assertEquals(
      CreatePullRequest.of("  fix the hook quoting  ", PullRequestHead.branch(Stable), Main).map(_.title),
      Right("fix the hook quoting"),
    )

  test("a blank title is rejected here rather than costing a round trip to a 422 with a raw Go message"):
    val rejected = CreatePullRequest.of("  \n\t ", PullRequestHead.branch(Stable), Main)

    assertEquals(rejected.left.map(_.field), Left("title"))
    assertEquals(rejected.left.map(_.message), Left("must not be blank"))

  test("an empty title is rejected as well as a whitespace one"):
    assertEquals(CreatePullRequest.of("", PullRequestHead.branch(Stable), Main).left.map(_.field), Left("title"))

  test("a head and a base that differ only by repository are both kept, and are not conflated"):
    val command = orFail(CreatePullRequest.of("t", PullRequestHead.branch(Main), Stable))

    assertEquals(command.head.value, "main")
    assertEquals(command.base, Stable)

  test("every create builder sets its own field and leaves every sibling alone"):
    val created = populatedCreate

    assertEquals(created.withBody("replaced"), created.copy(body = Some("replaced")))
    assertEquals(created.assignedTo(Vector("crystal")), created.copy(assignees = Vector("crystal")))
    assertEquals(created.labelled(Vector(Chore)), created.copy(labels = Vector(Chore)))
    assertEquals(created.inMilestone(Next), created.copy(milestone = Some(Next)))
    assertEquals(created.dueBy(Monday), created.copy(dueDate = Some(Monday)))

  test("assignedTo with an empty vector leaves the pull request unassigned rather than being ignored"):
    assertEquals(populatedCreate.assignedTo(Vector.empty).assignees, Vector.empty[String])

  test("labelled with an empty vector leaves the pull request unlabelled"):
    assertEquals(populatedCreate.labelled(Vector.empty).labels, Vector.empty[LabelId])

  // -- EditPullRequest ------------------------------------------------------

  test("an empty edit is a well-formed request that changes nothing, and every field says so"):
    assertEquals(EditPullRequest.Empty.title, None)
    assertEquals(EditPullRequest.Empty.body, None)
    assertEquals(EditPullRequest.Empty.assignees, None)
    assertEquals(EditPullRequest.Empty.labels, None)
    assertEquals(EditPullRequest.Empty.milestone, None)
    assertEquals(EditPullRequest.Empty.state, None)
    assertEquals(EditPullRequest.Empty.base, None)
    assertEquals(EditPullRequest.Empty.dueDate, None)
    assertEquals(EditPullRequest.Empty.unsetDueDate, false)
    assertEquals(EditPullRequest.Empty.allowMaintainerEdit, None)

  test("every edit builder sets its own field and leaves every sibling alone"):
    val edit = populatedEdit

    assertEquals(edit.withTitle("replaced"), edit.copy(title = Some("replaced")))
    assertEquals(edit.withBody("replaced"), edit.copy(body = Some("replaced")))
    assertEquals(edit.assignedTo(Vector("crystal")), edit.copy(assignees = Some(Vector("crystal"))))
    assertEquals(edit.labelled(Vector(Chore)), edit.copy(labels = Some(Vector(Chore))))
    assertEquals(edit.inMilestone(Next), edit.copy(milestone = Some(Next)))
    assertEquals(edit.close, edit.copy(state = Some(IssueStateChange.Close)))
    assertEquals(edit.reopen, edit.copy(state = Some(IssueStateChange.Reopen)))
    assertEquals(edit.withBase(Stable), edit.copy(base = Some(Stable)))
    assertEquals(edit.dueBy(Monday), edit.copy(dueDate = Some(Monday)))
    assertEquals(edit.withoutDueDate, edit.copy(unsetDueDate = true))
    assertEquals(edit.allowingMaintainerEdit, edit.copy(allowMaintainerEdit = Some(true)))
    assertEquals(edit.forbiddingMaintainerEdit, edit.copy(allowMaintainerEdit = Some(false)))

  test("assignees and labels replace rather than add, so present-and-empty clears them"):
    assertEquals(EditPullRequest.Empty.assignedTo(Vector.empty).assignees, Some(Vector.empty[String]))
    assertEquals(EditPullRequest.Empty.labelled(Vector.empty).labels, Some(Vector.empty[LabelId]))

  test("an untouched assignee list stays absent, which is what leaves the assignees alone"):
    assertEquals(EditPullRequest.Empty.withTitle("t").assignees, None)
    assertEquals(EditPullRequest.Empty.withTitle("t").labels, None)

  test("clearing a deadline is its own flag and not a deadline of some sentinel instant"):
    val cleared = EditPullRequest.Empty.withoutDueDate

    assertEquals(cleared.unsetDueDate, true)
    assertEquals(cleared.dueDate, None)

  test("setting a deadline does not raise the unset flag"):
    val deadlined = EditPullRequest.Empty.dueBy(Friday)

    assertEquals(deadlined.dueDate, Some(Friday))
    assertEquals(deadlined.unsetDueDate, false)

  test("close and reopen set opposite transitions, and the later call wins"):
    assertEquals(EditPullRequest.Empty.close.state, Some(IssueStateChange.Close))
    assertEquals(EditPullRequest.Empty.close.reopen.state, Some(IssueStateChange.Reopen))

  test("closing a pull request is not merging it — the edit carries no merge of any kind"):
    assertEquals(EditPullRequest.Empty.close.state.map(_.wireValue), Some("closed"))

  test("maintainer edit is a tri-state: absent leaves the setting alone, present sets it either way"):
    assertEquals(EditPullRequest.Empty.allowMaintainerEdit, None)
    assertEquals(EditPullRequest.Empty.allowingMaintainerEdit.allowMaintainerEdit, Some(true))
    assertEquals(EditPullRequest.Empty.forbiddingMaintainerEdit.allowMaintainerEdit, Some(false))
    assertEquals(EditPullRequest.Empty.allowingMaintainerEdit.forbiddingMaintainerEdit.allowMaintainerEdit, Some(false))

  test("retargeting sets the base and touches nothing else, since it changes what the pull request means"):
    val retargeted = EditPullRequest.Empty.withBase(Main)

    assertEquals(retargeted.base, Some(Main))
    assertEquals(retargeted.title, None)
    assertEquals(retargeted.state, None)

  // -- MergePullRequest -----------------------------------------------------

  test("a merge command starts from a style, with every flag off and every option absent"):
    val command = MergePullRequest.using(MergeStyle.Squash)

    assertEquals(command.style, MergeStyle.Squash)
    assertEquals(command.title, None)
    assertEquals(command.message, None)
    assertEquals(command.headCommit, None)
    assertEquals(command.mergedCommit, None)
    assertEquals(command.deleteBranchAfterMerge, false)
    assertEquals(command.forceMerge, false)
    assertEquals(command.mergeWhenChecksSucceed, false)

  test("the style a merge was started from is the style it keeps"):
    assertEquals(MergeStyle.values.toList.map(style => MergePullRequest.using(style).style), MergeStyle.values.toList)

  test("every merge builder sets its own field and leaves every sibling alone"):
    val merge = populatedMerge

    assertEquals(merge.withTitle("replaced"), merge.copy(title = Some("replaced")))
    assertEquals(merge.withMessage("replaced"), merge.copy(message = Some("replaced")))
    assertEquals(merge.expecting(Landed), merge.copy(headCommit = Some(Landed)))
    assertEquals(merge.recordingMerge(Head), merge.copy(mergedCommit = Some(Head)))
    assertEquals(merge.deletingSourceBranch, merge.copy(deleteBranchAfterMerge = true))
    assertEquals(merge.forcing, merge.copy(forceMerge = true))
    assertEquals(merge.whenChecksSucceed, merge.copy(mergeWhenChecksSucceed = true))

  test("expecting a head is what makes a repeated merge safe, and it is off unless asked for"):
    assertEquals(MergePullRequest.using(MergeStyle.Merge).headCommit, None)
    assertEquals(MergePullRequest.using(MergeStyle.Merge).expecting(Head).headCommit, Some(Head))

  test("the expected head and the already-merged commit are separate fields and do not overwrite each other"):
    val command = MergePullRequest.using(MergeStyle.ManuallyMerged).expecting(Head).recordingMerge(Landed)

    assertEquals(command.headCommit, Some(Head))
    assertEquals(command.mergedCommit, Some(Landed))

  test("deleting the source branch is never a default — only deletingSourceBranch turns it on"):
    assertEquals(MergePullRequest.using(MergeStyle.Merge).deleteBranchAfterMerge, false)
    assertEquals(MergePullRequest.using(MergeStyle.Merge).forcing.deleteBranchAfterMerge, false)
    assertEquals(MergePullRequest.using(MergeStyle.Merge).deletingSourceBranch.deleteBranchAfterMerge, true)

  test("forcing and scheduling are independent flags, so one cannot be mistaken for the other"):
    val forced = MergePullRequest.using(MergeStyle.Merge).forcing

    assertEquals(forced.forceMerge, true)
    assertEquals(forced.mergeWhenChecksSucceed, false)

    val scheduled = MergePullRequest.using(MergeStyle.Merge).whenChecksSucceed

    assertEquals(scheduled.mergeWhenChecksSucceed, true)
    assertEquals(scheduled.forceMerge, false)

  test("a fast-forward-only merge still accepts a title, which the instance then ignores"):
    assertEquals(MergePullRequest.using(MergeStyle.FastForwardOnly).withTitle("t").title, Some("t"))

  private def populatedCreate: CreatePullRequest =
    orFail(CreatePullRequest.of("original", PullRequestHead.branch(Stable), Main))
      .withBody("original body")
      .assignedTo(Vector("earl-warren"))
      .labelled(Vector(Bug))
      .inMilestone(Release)
      .dueBy(Friday)

  private def populatedEdit: EditPullRequest =
    EditPullRequest(
      title               = Some("original"),
      body                = Some("original body"),
      assignees           = Some(Vector("earl-warren")),
      labels              = Some(Vector(Bug)),
      milestone           = Some(Release),
      state               = Some(IssueStateChange.Reopen),
      base                = Some(Main),
      dueDate             = Some(Friday),
      unsetDueDate        = false,
      allowMaintainerEdit = Some(false),
    )

  private def populatedMerge: MergePullRequest =
    MergePullRequest(
      style                  = MergeStyle.RebaseMerge,
      title                  = Some("original"),
      message                = Some("original body"),
      headCommit             = Some(Head),
      mergedCommit           = Some(Landed),
      deleteBranchAfterMerge = false,
      forceMerge             = false,
      mergeWhenChecksSucceed = false,
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
