package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue, WireValues}
import com.worxbend.codeberg4s.pulls.ReviewRequest

/** Forgejo's `PullReviewRequestOptions` request model — the body of '''both''' the `POST` and the `DELETE` on
  * `/repos/{owner}/{repo}/pulls/{index}/requested_reviewers`.
  *
  * One renderer for two operations, because Forgejo declares the same schema for both and the difference is entirely in
  * the method. A `DELETE` carrying a JSON body is unusual but is what this endpoint requires; see
  * [[com.worxbend.codeberg4s.pulls.PullRequestApi.removeReviewRequests]].
  *
  * '''Only a non-empty list contributes a key.''' `reviewers` holds account handles and `team_reviewers` holds team
  * names, and Forgejo will not look a value up in the other list — so an empty vector is emitted as no key rather than
  * as `[]`, which would be a statement the caller never made. A body with neither key is what
  * [[com.worxbend.codeberg4s.pulls.ReviewRequest.Empty]] renders to, and Forgejo answers it with a `422`.
  */
private[codeberg4s] object PullReviewRequestOptionsDto:

  /** Renders `request` as the JSON body to send. */
  def render(request: ReviewRequest): String =
    Json.render(JsonValue.Obj.from(fields(request)))

  private def fields(request: ReviewRequest): List[(String, JsonValue)] =
    List(
      Option.when(request.reviewers.nonEmpty)("reviewers"  -> WireValues.strings(request.reviewers.map(_.value))),
      Option.when(request.teams.nonEmpty)("team_reviewers" -> WireValues.strings(request.teams)),
    ).flatten
