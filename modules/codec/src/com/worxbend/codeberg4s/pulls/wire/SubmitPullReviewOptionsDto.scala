package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.pulls.SubmitReview

/** Forgejo's `SubmitPullReviewOptions` request model — the body of
  * `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}`.
  *
  * Two keys, of which `event` is always present: [[com.worxbend.codeberg4s.pulls.SubmitReview]] makes it mandatory
  * because a submission that says nothing is a `422`. `body` is emitted only when the caller set one, so an absent key
  * leaves whatever text the pending review already carried rather than blanking it.
  */
private[codeberg4s] object SubmitPullReviewOptionsDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: SubmitReview): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: SubmitReview): List[(String, JsonValue)] =
    List(
      Some("event" -> JsonValue.Str(command.event.wireValue)),
      command.body.map(text => "body" -> JsonValue.Str(text)),
    ).flatten
