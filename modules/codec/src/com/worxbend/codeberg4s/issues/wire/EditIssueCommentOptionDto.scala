package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.issues.EditComment

/** Forgejo's `EditIssueCommentOption` request model — the body of `PATCH /repos/{owner}/{repo}/issues/comments/{id}`
  * and of its deprecated per-issue twin.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives: nothing ever '''reads''' this
  * shape, so a type whose only purpose is to be serialised once would be a class with no inhabitants worth naming.
  *
  * Derived from `spec/swagger.v1.json`; no golden capture of this request exists.
  *
  * `body` is the model's one required property and is always emitted. `updated_at` is emitted only when the caller set
  * it, because the spec notes it "needs admin or repository owner permission" and sending it unasked would make every
  * ordinary edit carry a field the token is not entitled to.
  */
private[codeberg4s] object EditIssueCommentOptionDto:

  /** Renders `command` as the JSON body to `PATCH`. */
  def render(command: EditComment): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: EditComment): List[(String, JsonValue)] =
    List(
      Some("body" -> JsonValue.Str(command.body)),
      command.updatedAt.map(moment => "updated_at" -> JsonValue.Str(WireInstant.render(moment))),
    ).flatten
