package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** One review of a pull request, as `GET /repos/{owner}/{repo}/pulls/{index}/reviews` reports it.
  *
  * Owned by this group per `docs/LEDGER.md`. A "review" here is any row on that endpoint, which includes the review
  * '''requests''' Forgejo records when someone is asked to look at a pull request: on `golden/pull/reviews-list.json`
  * two of the three rows are [[ReviewState.RequestReview]] with an empty body and no commit, and only the third is an
  * approval. [[state]] is what separates them.
  *
  * Forgejo's `PullReview` also carries a `team` — a review requested from a whole team rather than an account. That
  * field is `null` on all three fixture rows and its payload is the organisation wave's `Team` model, so it is kept on
  * [[com.worxbend.codeberg4s.pulls.wire.ReviewDto]] and not modelled here.
  *
  * @param id
  *   the instance-wide identifier; see [[ReviewId]]
  * @param state
  *   what the review said, absent when the instance sent a value this library does not recognise — including the `""`
  *   it sends for a stateless row. See [[ReviewState.parse]]
  * @param body
  *   the review's summary text, absent when the reviewer left none. Empty on every row of the fixture, which
  *   [[com.worxbend.codeberg4s.codec.JsonFields.text]] reads as absence
  * @param author
  *   the account that reviewed, or whose review was requested
  * @param commit
  *   the head commit the review was made against, absent for a review request — nothing has been reviewed yet, so there
  *   is nothing to pin it to
  * @param isStale
  *   whether the pull request has moved on since [[commit]], which makes the verdict advisory rather than current
  * @param isOfficial
  *   whether the review counts towards the base branch's required-approval rule. A review from someone without write
  *   access is recorded but not official
  * @param isDismissed
  *   whether a maintainer dismissed the review, which removes it from the approval count without deleting it
  * @param commentCount
  *   how many inline diff comments the review carries; `0` for a review request and for a summary-only review
  * @param htmlUrl
  *   the browser URL of the review, absent for a review request — `""` on the fixture's two request rows and a real
  *   anchor on the approval
  */
final case class Review(
    id: ReviewId,
    state: Option[ReviewState],
    body: Option[String],
    author: Option[User],
    commit: Option[CommitSha],
    isStale: Boolean,
    isOfficial: Boolean,
    isDismissed: Boolean,
    commentCount: Long,
    htmlUrl: Option[String],
    pullRequestUrl: Option[String],
    submittedAt: Option[Instant],
    updatedAt: Option[Instant],
)
