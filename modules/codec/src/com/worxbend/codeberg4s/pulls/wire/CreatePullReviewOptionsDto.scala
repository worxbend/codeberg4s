package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.pulls.CreateReview

/** Forgejo's `CreatePullReviewOptions` request model — the body of `POST /repos/{owner}/{repo}/pulls/{index}/reviews`.
  *
  * An object rather than a case class, for the reason [[CreatePullRequestOptionDto]] gives.
  *
  * '''Only what the caller set is emitted.''' The definition declares no required property, and every key here means
  * something different when absent: no `event` leaves the review pending, no `commit_id` pins it to whatever the head
  * is at the moment Forgejo handles the request, and no `comments` makes it summary-only. Sending `"body": ""` or
  * `"comments": []` would assert a default this library was never told.
  *
  * The `event` values are [[com.worxbend.codeberg4s.pulls.ReviewState]]'s own wire spellings — upper case with
  * underscores, unlike the rest of the API — written by [[com.worxbend.codeberg4s.pulls.ReviewState.wireValue]] rather
  * than re-spelled here.
  */
private[codeberg4s] object CreatePullReviewOptionsDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateReview): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: CreateReview): List[(String, JsonValue)] =
    List(
      command.body.map(text   => "body" -> JsonValue.Str(text)),
      command.event.map(state => "event" -> JsonValue.Str(state.wireValue)),
      command.commit.map(sha  => "commit_id" -> JsonValue.Str(sha.value)),
      Option.when(command.comments.nonEmpty)(
        "comments" -> JsonValue.Arr.from(command.comments.map(NewReviewCommentDto.obj))
      ),
    ).flatten
