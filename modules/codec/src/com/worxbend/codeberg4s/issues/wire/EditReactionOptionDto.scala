package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.issues.ReactionContent

/** Forgejo's `EditReactionOption` request model — the body of every reaction call, whether it adds or removes.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of this request exists.
  *
  * ==A DELETE with a body==
  *
  * `DELETE /repos/{owner}/{repo}/issues/{index}/reactions` takes this same object, because "which reaction" is not in
  * the URL. That is unusual — RFC 9110 permits a body on `DELETE` but gives it no defined semantics — and it is why
  * [[com.worxbend.codeberg4s.issues.IssueReactionApi]]'s removal methods build a request with a body where every other
  * `DELETE` in this library builds one without.
  *
  * The model declares one optional property, `content`, and it is always emitted: a reaction call that names no
  * reaction has nothing to do.
  */
private[codeberg4s] object EditReactionOptionDto:

  /** Renders `content` as the JSON body to send. */
  def render(content: ReactionContent): String =
    Json.render(JsonValue.Obj("content" -> JsonValue.Str(content.value)))
