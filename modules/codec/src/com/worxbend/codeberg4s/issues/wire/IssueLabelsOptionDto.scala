package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.issues.LabelRef
import com.worxbend.codeberg4s.issues.LabelRemoval
import com.worxbend.codeberg4s.issues.LabelUpdate

/** Forgejo's `IssueLabelsOption` and `DeleteLabelsOption` request models — the bodies of the four per-issue label
  * calls.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of these requests exists.
  *
  * ==A heterogeneous array, and the spec means it==
  *
  * `IssueLabelsOption.labels` is declared as `{"type": "array", "items": {}}` — an array with an '''empty''' item
  * schema — carrying the note "Labels can be a list of integers representing label IDs or a list of strings
  * representing label names". Rendering therefore emits a JSON number for
  * [[com.worxbend.codeberg4s.issues.LabelRef.ById]] and a JSON string for
  * [[com.worxbend.codeberg4s.issues.LabelRef.ByName]], and a single request may mix the two. That is the one place in
  * this library where the element type of an emitted array is not fixed, which is exactly why
  * [[com.worxbend.codeberg4s.issues.LabelRef]] exists rather than a `Vector[Any]`.
  *
  * ==Two models, one renderer==
  *
  * `DeleteLabelsOption` is `IssueLabelsOption` without the `labels` key. [[renderRemoval]] emits `{}` for the ordinary
  * case rather than omitting the body altogether, because the operations declare a body parameter and an instance that
  * insists on a well-formed one must get it.
  */
private[codeberg4s] object IssueLabelsOptionDto:

  /** Renders `command` as the JSON body of the label add or replace. */
  def renderUpdate(command: LabelUpdate): String =
    val labels = JsonValue.Arr.from(command.labels.map(reference))
    val fields = List(
      Some("labels" -> (labels: JsonValue)),
      command.updatedAt.map(moment => "updated_at" -> JsonValue.Str(WireInstant.render(moment))),
    ).flatten

    Json.render(JsonValue.Obj.from(fields))

  /** Renders `command` as the JSON body of the label clear or single removal; `{}` when it says nothing. */
  def renderRemoval(command: LabelRemoval): String =
    val fields = command.updatedAt.map(moment => "updated_at" -> JsonValue.Str(WireInstant.render(moment))).toList

    Json.render(JsonValue.Obj.from(fields))

  private def reference(label: LabelRef): JsonValue =
    label match
      case LabelRef.ById(id)     => WireNumbers.identifier(id.value)
      case LabelRef.ByName(name) => JsonValue.Str(name.value)
