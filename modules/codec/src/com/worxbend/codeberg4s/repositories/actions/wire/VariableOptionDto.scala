package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.repositories.actions.{CreateVariable, UpdateVariable}

/** Forgejo's `CreateVariableOption` and `UpdateVariableOption` request models — the bodies of `POST` and `PUT` on
  * `/repos/{owner}/{repo}/actions/variables/{variablename}`.
  *
  * One object for two models because they share the one key that matters and differ in exactly one more: `value` is
  * required by both, and `name` is accepted only by the update, where it moves the variable. Splitting them into two
  * files would put the `value` spelling in two places, which is what rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] exists to prevent.
  *
  * Note that the variable's content is sent as `value` and read back as `data`; see [[ActionVariableDto]].
  */
private[codeberg4s] object VariableOptionDto:

  /** The wire key a variable's content is sent under. */
  val ValueKey: String = "value"

  /** The wire key an update sends a new name under. */
  val NameKey: String = "name"

  /** Renders `command` as the JSON body to `POST`.
    *
    * Only `value` is emitted. `CreateVariableOption` has no other property — the name is a path segment on this
    * endpoint, not a field.
    */
  def renderCreate(command: CreateVariable): String =
    Json.render(JsonValue.Obj(ValueKey -> JsonValue.Str(command.value)))

  /** Renders `command` as the JSON body to `PUT`.
    *
    * `value` is always emitted, because the spec marks it required even when the caller only meant to rename. `name` is
    * emitted only when the command carries one: the spec says an empty `name` leaves the variable where it is, and
    * sending `""` to mean "no change" would be indistinguishable from a caller who meant to send a name and built an
    * empty one.
    */
  def renderUpdate(command: UpdateVariable): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: UpdateVariable): List[(String, JsonValue)] =
    List(
      Some(ValueKey -> JsonValue.Str(command.value)),
      command.renamedTo.map(name => NameKey -> JsonValue.Str(name.value)),
    ).flatten
