package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue

import java.time.Instant

/** Forgejo's `EditDeadlineOption` request model — the body of `POST /repos/{owner}/{repo}/issues/{index}/deadline`.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of this request exists.
  *
  * `due_date` is the model's one property and the only one the spec marks `required` anywhere in this group's request
  * models. It is always emitted, rendered by [[WireInstant]] in the RFC-3339 form Go parses — a malformed one comes
  * back as a `422` carrying a raw Go parse error, per `docs/HAZARDS.md` §4.
  *
  * '''There is no way to clear a deadline through this endpoint.''' `due_date` is required, so a caller who wants no
  * deadline uses [[com.worxbend.codeberg4s.issues.EditIssue.withoutDueDate]] on the issue edit instead, which sends the
  * separate `unset_due_date` flag Forgejo needs.
  */
private[codeberg4s] object EditDeadlineOptionDto:

  /** Renders `dueDate` as the JSON body to `POST`. */
  def render(dueDate: Instant): String =
    Json.render(JsonValue.Obj("due_date" -> JsonValue.Str(WireInstant.render(dueDate))))
