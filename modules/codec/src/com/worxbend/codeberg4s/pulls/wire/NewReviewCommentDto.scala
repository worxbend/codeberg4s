package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.issues.wire.WireNumbers
import com.worxbend.codeberg4s.pulls.NewReviewComment

/** Forgejo's `CreatePullReviewComment` request model.
  *
  * Sent in two places, which is why the object exposes both halves: [[render]] is the whole body of
  * `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments`, whose `CreatePullReviewCommentOptions` schema is a
  * bare `$ref` to this definition, and [[obj]] is one element of `CreatePullReviewOptions.comments`. Writing the field
  * spellings once is rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]].
  *
  * '''Only what the caller set is emitted.''' `new_position`, `old_position` and `extra_lines_count` are plain `int64`
  * on Forgejo's side, so `0` and an absent key mean the same thing there — and this library sends the absent key,
  * because a `0` it invented would look like a caller statement. Which of the two positions appears is decided by
  * [[com.worxbend.codeberg4s.pulls.NewReviewComment]]'s constructors, never here.
  *
  * `body` and `path` are always present: both are validated and non-blank by the time a command exists.
  */
private[codeberg4s] object NewReviewCommentDto:

  /** Renders `comment` as the JSON body of the create-review-comment endpoint. */
  def render(comment: NewReviewComment): String =
    ujson.write(obj(comment))

  /** `comment` as a JSON object, for embedding in `CreatePullReviewOptions.comments`. */
  def obj(comment: NewReviewComment): ujson.Value =
    ujson.Obj.from(fields(comment))

  private def fields(comment: NewReviewComment): List[(String, ujson.Value)] =
    List(
      Some("body" -> ujson.Str(comment.body)),
      Some("path" -> ujson.Str(comment.path)),
      comment.newPosition.map(line     => "new_position" -> WireNumbers.identifier(line)),
      comment.oldPosition.map(line     => "old_position" -> WireNumbers.identifier(line)),
      comment.extraLinesCount.map(span => "extra_lines_count" -> WireNumbers.identifier(span)),
    ).flatten
