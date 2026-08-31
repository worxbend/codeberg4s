package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.pulls.DismissReview

/** Forgejo's `DismissPullReviewOptions` request model — the body of
  * `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/dismissals`.
  *
  * '''Only what the caller set is emitted''', and `priors` is emitted only when `true`: `false` and absent mean the
  * same thing to Forgejo, and sending `"priors": false` would be this library asserting a reach the caller never asked
  * for. An empty body — which is what [[com.worxbend.codeberg4s.pulls.DismissReview.Empty]] renders to — is a valid
  * dismissal with no recorded reason.
  */
private[codeberg4s] object DismissPullReviewOptionsDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: DismissReview): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: DismissReview): List[(String, JsonValue)] =
    List(
      command.message.map(text => "message" -> JsonValue.Str(text)),
      Option.when(command.priors)("priors" -> JsonValue.Bool(true)),
    ).flatten
