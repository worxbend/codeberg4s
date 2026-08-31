package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.issues.wire.WireNumbers
import com.worxbend.codeberg4s.pulls.EditPullRequest

/** Forgejo's `EditPullRequestOption` request model — the body of `PATCH /repos/{owner}/{repo}/pulls/{index}`.
  *
  * An object rather than a case class, for the reason [[CreatePullRequestOptionDto]] gives.
  *
  * '''Only what the caller set is emitted, and here that is the entire semantics of the request.''' A `PATCH` leaves
  * alone whatever the body does not mention, so an unset field must not become a key. Three consequences are worth
  * spelling out because they are easy to get wrong:
  *
  *   - `assignees` and `labels` are emitted whenever the corresponding builder was called, '''including with an empty
  *     vector''' — Forgejo replaces each list rather than adding to it, so `[]` is how a caller clears one, and it must
  *     be distinguishable from never having mentioned it;
  *   - clearing a deadline is `unset_due_date: true`, not `due_date: null`, because Forgejo cannot tell a null from an
  *     absent key. The flag is emitted only when set;
  *   - `allow_maintainer_edit` is emitted for both `true` and `false`, since the caller asking to switch it off is a
  *     real request and is not the same as leaving it alone. That is why the field is an `Option[Boolean]` and why
  *     [[com.worxbend.codeberg4s.pulls.EditPullRequest]] offers two named methods rather than one taking a `Boolean`.
  *
  * An [[com.worxbend.codeberg4s.pulls.EditPullRequest.Empty]] renders as `{}`, which is a well-formed request that
  * changes nothing.
  */
private[codeberg4s] object EditPullRequestOptionDto:

  /** Renders `command` as the JSON body to `PATCH`. */
  def render(command: EditPullRequest): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: EditPullRequest): List[(String, JsonValue)] =
    List(
      command.title.map(text       => "title" -> JsonValue.Str(text)),
      command.body.map(text        => "body" -> JsonValue.Str(text)),
      command.assignees.map(logins => "assignees" -> WireNumbers.strings(logins)),
      command.labels.map(ids       => "labels" -> WireNumbers.identifiers(ids.map(_.value))),
      command.milestone.map(id     => "milestone" -> WireNumbers.identifier(id.value)),
      command.state.map(change     => "state" -> JsonValue.Str(change.wireValue)),
      command.base.map(branch      => "base" -> JsonValue.Str(branch.value)),
      command.dueDate.map(moment   => "due_date" -> JsonValue.Str(Timestamps.render(moment))),
      Option.when(command.unsetDueDate)("unset_due_date" -> JsonValue.Bool(true)),
      command.allowMaintainerEdit.map(allowed => "allow_maintainer_edit" -> JsonValue.Bool(allowed)),
    ).flatten
