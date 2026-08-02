package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.organizations.Organization
import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.organizations.TeamId
import com.worxbend.codeberg4s.organizations.TeamPermission
import com.worxbend.codeberg4s.pulls.CreateReview
import com.worxbend.codeberg4s.pulls.DiffFormat
import com.worxbend.codeberg4s.pulls.DiffRequest
import com.worxbend.codeberg4s.pulls.DismissReview
import com.worxbend.codeberg4s.pulls.NewReviewComment
import com.worxbend.codeberg4s.pulls.ReviewRequest
import com.worxbend.codeberg4s.pulls.ReviewState
import com.worxbend.codeberg4s.pulls.SubmitReview
import com.worxbend.codeberg4s.pulls.UpdateStyle
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.Username

import munit.FunSuite

/** The five review-shaped request bodies this group sends, and the two query strings.
  *
  * Asserted as rendered text rather than through a stub backend, because the interesting decision in every one of them
  * is '''which keys appear at all''': an absent key lets Forgejo apply its own default, and an explicit `false` or `[]`
  * would be this library asserting something the caller never said.
  */
final class ReviewBodySuite extends FunSuite:

  private val Sha: CommitSha = orFail(CommitSha.from("48079baa8d387f3ab770cc144c367409ddc2a879"))

  private val Reviewer: Username = orFail(Username.from("mfenniak"))

  // --- creating a review ----------------------------------------------------

  test("an empty review renders an empty object, which Forgejo accepts as a pending draft"):
    assertEquals(CreatePullReviewOptionsDto.render(CreateReview.Empty), "{}")

  test("a submitted approval sends the event in ReviewState's upper-case spelling"):
    val command = CreateReview.Empty.saying(ReviewState.Approved).withBody("looks good")

    assertEquals(
      CreatePullReviewOptionsDto.render(command),
      """{"body":"looks good","event":"APPROVED"}""",
    )

  test("a commit the review is pinned to is sent as commit_id"):
    val command = CreateReview.Empty.saying(ReviewState.RequestChanges).against(Sha)

    assertEquals(
      CreatePullReviewOptionsDto.render(command),
      s"""{"event":"REQUEST_CHANGES","commit_id":"${Sha.value}"}""",
    )

  test("inline remarks are embedded as an array, in the order they were added"):
    val command = CreateReview.Empty
      .commenting(orFail(NewReviewComment.onNewLine("a.go", 4L, "one")))
      .commenting(orFail(NewReviewComment.onOldLine("b.go", 9L, "two")))

    assertEquals(
      CreatePullReviewOptionsDto.render(command),
      """{"comments":[{"body":"one","path":"a.go","new_position":4},""" +
        """{"body":"two","path":"b.go","old_position":9}]}""",
    )

  test("an empty remark vector is not a statement, so it contributes no key"):
    assertEquals(CreatePullReviewOptionsDto.render(CreateReview.Empty.commentingAll(Vector.empty)), "{}")

  // --- one inline remark on its own -----------------------------------------

  test("a remark posted on its own is the very same object the array holds"):
    val remark = orFail(NewReviewComment.onNewLine("modules/git/hook.go", 42L, "still wrong"))

    assertEquals(
      NewReviewCommentDto.render(remark),
      """{"body":"still wrong","path":"modules/git/hook.go","new_position":42}""",
    )

  test("a remark about the whole file sends neither position, because zero would mean a line"):
    val remark = orFail(NewReviewComment.onFile("modules/git/hook.go", "rewrite this"))

    assertEquals(
      NewReviewCommentDto.render(remark),
      """{"body":"rewrite this","path":"modules/git/hook.go"}""",
    )

  test("a span is emitted only when the caller set one"):
    val remark = orFail(NewReviewComment.onNewLine("a.go", 1L, "x")).spanning(3L)

    assertEquals(
      NewReviewCommentDto.render(remark),
      """{"body":"x","path":"a.go","new_position":1,"extra_lines_count":3}""",
    )

  // --- submitting -----------------------------------------------------------

  test("a submission always carries its event, because a submission that says nothing is a 422"):
    assertEquals(SubmitPullReviewOptionsDto.render(SubmitReview.saying(ReviewState.Comment)), """{"event":"COMMENT"}""")

  test("a submission body is added only when set, so an unset one keeps the draft's text"):
    assertEquals(
      SubmitPullReviewOptionsDto.render(SubmitReview.saying(ReviewState.Approved).withBody("ship it")),
      """{"event":"APPROVED","body":"ship it"}""",
    )

  // --- dismissing -----------------------------------------------------------

  test("a dismissal with nothing to say renders an empty object"):
    assertEquals(DismissPullReviewOptionsDto.render(DismissReview.Empty), "{}")

  test("priors is emitted only when true, because false and absent mean the same thing to Forgejo"):
    assertEquals(
      DismissPullReviewOptionsDto.render(DismissReview.Empty.withMessage("superseded").includingPriors),
      """{"message":"superseded","priors":true}""",
    )

  // --- review requests ------------------------------------------------------

  test("accounts and teams land under their own keys"):
    val request = ReviewRequest.of(Reviewer).requestingTeam(team("reviewers"))

    assertEquals(
      PullReviewRequestOptionsDto.render(request),
      """{"reviewers":["mfenniak"],"team_reviewers":["reviewers"]}""",
    )

  test("an empty list contributes no key, so a request naming nobody is an empty object"):
    assertEquals(PullReviewRequestOptionsDto.render(ReviewRequest.Empty), "{}")

  test("asking only a team sends only the team key"):
    assertEquals(
      PullReviewRequestOptionsDto.render(ReviewRequest.ofTeam(team("owners"))),
      """{"team_reviewers":["owners"]}""",
    )

  // --- queries --------------------------------------------------------------

  test("the diff query is empty unless binary changes were asked for"):
    assertEquals(PullRequestQueries.diff(DiffRequest.of(DiffFormat.Patch)), Nil)

  test("binary is sent as true when the caller asked, and never as false"):
    assertEquals(
      PullRequestQueries.diff(DiffRequest.of(DiffFormat.Diff).includingBinary),
      List("binary" -> "true"),
    )

  test("the update style is always sent, so the recorded request says which of the two was meant"):
    assertEquals(PullRequestQueries.update(UpdateStyle.Merge), List("style" -> "merge"))
    assertEquals(PullRequestQueries.update(UpdateStyle.Rebase), List("style" -> "rebase"))

  // --- harness --------------------------------------------------------------

  private def team(name: String): Team =
    Team(
      id                      = orFail(TeamId.from(1L)),
      name                    = name,
      description             = None,
      organization            = Option.empty[Organization],
      permission              = Option.empty[TeamPermission],
      units                   = Vector.empty,
      unitPermissions         = Map.empty,
      canCreateOrgRepo        = false,
      includesAllRepositories = false,
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
