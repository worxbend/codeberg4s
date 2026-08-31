package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.repositories.access.{CreateTagProtection, EditTagProtection}
import com.worxbend.codeberg4s.users.Username

/** Forgejo's `CreateTagProtectionOption` and `EditTagProtectionOption` request models — the bodies of `POST` on
  * `/repos/{owner}/{repo}/tag_protections` and `PATCH` on `/repos/{owner}/{repo}/tag_protections/{id}`.
  *
  * One object for two models because they declare the same three properties and differ only in what "absent" means; see
  * [[com.worxbend.codeberg4s.repositories.actions.wire.VariableOptionDto]] for the same shape and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] for why the spellings are not written twice.
  *
  * ==The two renderers treat absence differently, and that is the whole difference==
  *
  *   - [[renderCreate]] emits all three properties, always. A create has nothing to preserve: an omitted whitelist is
  *     an empty whitelist either way, so spelling it out means the request says what the resulting rule will be.
  *   - [[renderEdit]] emits only what the caller stated. A `PATCH` that named every property would clear a whitelist
  *     the caller never mentioned, which on this surface is a rule that still exists and no longer stops anyone.
  *     `Some(Vector.empty)` still renders as `[]`, because clearing a whitelist on purpose has to be expressible.
  */
private[codeberg4s] object TagProtectionOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def renderCreate(command: CreateTagProtection): String =
    Json.render(
      JsonValue.Obj(
        TagProtectionWire.NamePattern        -> JsonValue.Str(command.namePattern.value),
        TagProtectionWire.WhitelistUsernames -> logins(command.whitelistUsernames),
        TagProtectionWire.WhitelistTeams     -> texts(command.whitelistTeams),
      )
    )

  /** Renders `command` as the JSON body to `PATCH`. A command that states nothing renders to `{}`. */
  def renderEdit(command: EditTagProtection): String =
    Json.render(JsonValue.Obj.from(editFields(command)))

  private def editFields(command: EditTagProtection): List[(String, JsonValue)] =
    List(
      command.namePattern.map(pattern       => TagProtectionWire.NamePattern -> JsonValue.Str(pattern.value)),
      command.whitelistUsernames.map(values => TagProtectionWire.WhitelistUsernames -> logins(values)),
      command.whitelistTeams.map(values     => TagProtectionWire.WhitelistTeams -> texts(values)),
    ).flatten

  private def logins(values: Vector[Username]): JsonValue =
    JsonValue.Arr.from(values.map(name => JsonValue.Str(name.value)))

  private def texts(values: Vector[String]): JsonValue =
    JsonValue.Arr.from(values.map(JsonValue.Str.apply))
