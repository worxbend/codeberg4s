package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.repositories.CommitSha

/** Everything `POST /repos/{owner}/{repo}/pulls/{index}/reviews` may be told, as one value.
  *
  * Forgejo's `CreatePullReviewOptions`. One call posts a summary, an intent, and any number of inline remarks at once,
  * which is what the web UI's "submit review" button does; posting the remarks one at a time through
  * [[com.worxbend.codeberg4s.pulls.PullRequestApi.createReviewComment]] produces a different thing — comments attached
  * to a pending review — and is the reason both endpoints exist.
  *
  * ==The event decides whether a review is submitted or left pending==
  *
  * [[event]] is Forgejo's `ReviewStateType`, and this library reuses [[ReviewState]] for it rather than declaring a
  * near-duplicate enum. Only four of the five cases are meaningful as an '''event''':
  *
  *   - [[ReviewState.Approved]], [[ReviewState.RequestChanges]] and [[ReviewState.Comment]] submit the review
  *     immediately;
  *   - [[ReviewState.Pending]] — and an absent event, which Forgejo treats the same way — leaves it as the reviewer's
  *     own unsent draft, to be finished later with [[com.worxbend.codeberg4s.pulls.PullRequestApi.submitReview]];
  *   - [[ReviewState.RequestReview]] is not an event at all. Forgejo rejects it with a `422`, because asking someone
  *     else to review is [[com.worxbend.codeberg4s.pulls.PullRequestApi.requestReviews]] and a different endpoint.
  *
  * '''Only what is set is sent''', so an unset field lets the instance apply its own default rather than this library's
  * idea of one.
  *
  * @param body
  *   the review's summary text. Forgejo requires one for [[ReviewState.Comment]] and for
  *   [[ReviewState.RequestChanges]], and answers `422` without it
  * @param event
  *   what the review says; see the note above
  * @param commit
  *   the head commit the review is made against, absent to let Forgejo pin the review to whatever the head is now.
  *   Naming it explicitly is what keeps a review from silently attaching to commits the reviewer never read
  * @param comments
  *   the inline remarks to post with the review, in order; empty for a summary-only review
  */
final case class CreateReview(
    body: Option[String],
    event: Option[ReviewState],
    commit: Option[CommitSha],
    comments: Vector[NewReviewComment],
):

  /** Sets the review's summary text, as Markdown source. */
  def withBody(text: String): CreateReview = copy(body = Some(text))

  /** Submits the review with `verdict`; see the class note on which cases are meaningful. */
  def saying(verdict: ReviewState): CreateReview = copy(event = Some(verdict))

  /** Pins the review to `sha`, so it cannot attach to commits pushed since the reviewer read the diff. */
  def against(sha: CommitSha): CreateReview = copy(commit = Some(sha))

  /** Adds one inline remark, keeping the ones already added. */
  def commenting(remark: NewReviewComment): CreateReview = copy(comments = comments.appended(remark))

  /** Replaces the inline remarks wholesale; an empty vector makes this a summary-only review. */
  def commentingAll(remarks: Vector[NewReviewComment]): CreateReview = copy(comments = remarks)

object CreateReview:

  /** A review that says nothing yet.
    *
    * Sent as-is this posts an empty pending review, which Forgejo accepts. Every useful command is this value with one
    * or more of the builders above applied.
    */
  val Empty: CreateReview =
    CreateReview(body = None, event = None, commit = None, comments = Vector.empty)
