package com.worxbend.codeberg4s.pulls

/** Everything `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/dismissals` may be told, as one value.
  *
  * Forgejo's `DismissPullReviewOptions`. Dismissing does '''not''' delete a review: it stays on the listing with
  * [[Review.isDismissed]] set and stops counting towards the base branch's required-approval rule. Deleting one is
  * [[com.worxbend.codeberg4s.pulls.PullRequestApi.deleteReview]] and is irreversible;
  * [[com.worxbend.codeberg4s.pulls.PullRequestApi.undismissReview]] undoes this.
  *
  * @param message
  *   the reason recorded against the dismissal, absent to record none. Worth setting — it is the only explanation the
  *   reviewer ever sees
  * @param priors
  *   whether to dismiss the reviewer's '''earlier''' reviews of the same pull request as well. `false` unless
  *   [[includingPriors]] was called: dismissing more than the review that was named is not a default this library picks
  */
final case class DismissReview(message: Option[String], priors: Boolean):

  /** Records `text` as the reason for the dismissal. */
  def withMessage(text: String): DismissReview = copy(message = Some(text))

  /** Also dismisses the same reviewer's earlier reviews of this pull request. */
  def includingPriors: DismissReview = copy(priors = true)

object DismissReview:

  /** A dismissal with no reason and no reach beyond the review it names. */
  val Empty: DismissReview = DismissReview(message = None, priors = false)
