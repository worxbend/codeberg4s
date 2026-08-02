package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.issues.IssueRef

/** Forgejo's `IssueMeta` request model — the body of all six blocking and dependency calls.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of these requests exists.
  *
  * All three properties are always emitted. None is marked required by the spec, but a reference that omits any of them
  * names no issue at all, and [[com.worxbend.codeberg4s.issues.IssueRef]] already guarantees all three are present and
  * valid — so there is nothing to omit conditionally.
  *
  * ==Two of the six calls are a DELETE with a body==
  *
  * `DELETE …/blocks` and `DELETE …/dependencies` take this object, because which link to sever is not in the URL. See
  * [[EditReactionOptionDto]] for the same shape and the same consequence.
  */
private[codeberg4s] object IssueMetaDto:

  /** Renders `reference` as the JSON body to send. */
  def render(reference: IssueRef): String =
    Json.render(
      JsonValue.Obj(
        "owner" -> JsonValue.Str(reference.owner.value),
        "repo"  -> JsonValue.Str(reference.repo.value),
        "index" -> WireNumbers.identifier(reference.number.value),
      )
    )
