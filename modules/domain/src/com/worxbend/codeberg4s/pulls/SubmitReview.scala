package com.worxbend.codeberg4s.pulls

/** Everything `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}` may be told, as one value.
  *
  * Forgejo's `SubmitPullReviewOptions`: it finishes a review that already exists as the reviewer's pending draft —
  * created by [[CreateReview]] with no event, and filled in with
  * [[com.worxbend.codeberg4s.pulls.PullRequestApi.createReviewComment]] — and turns it into a submitted verdict that
  * everyone can see.
  *
  * '''The event is required here''', unlike on [[CreateReview]], because submitting without saying what the review says
  * has no meaning: Forgejo answers `422`. [[ReviewState.Pending]] is equally meaningless — it is what the review
  * already is — and [[ReviewState.RequestReview]] belongs to a different endpoint entirely; both come back as a `422`
  * rather than as anything this library can catch in advance.
  *
  * @param event
  *   what the review says: [[ReviewState.Approved]], [[ReviewState.RequestChanges]] or [[ReviewState.Comment]]
  * @param body
  *   the summary text, absent to keep whatever the pending review already carried. Forgejo requires one for
  *   [[ReviewState.Comment]] and [[ReviewState.RequestChanges]] when the draft has none
  */
final case class SubmitReview(event: ReviewState, body: Option[String]):

  /** Sets the summary text, as Markdown source, replacing whatever the pending review carried. */
  def withBody(text: String): SubmitReview = copy(body = Some(text))

object SubmitReview:

  /** Starts a submission from the one thing Forgejo insists on.
    *
    * Total rather than an `Either`: [[ReviewState]] is a closed set, so there is nothing left to reject here. Whether
    * the particular case is a legal event is Forgejo's question and comes back as a `422` — see the class note.
    */
  def saying(verdict: ReviewState): SubmitReview =
    SubmitReview(event = verdict, body = None)
