package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.repositories.access.CreateDeployKey

/** Forgejo's `CreateKeyOption` request model — the body of `POST /repos/{owner}/{repo}/keys`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives: a request model is a rendering, not a value anyone holds.
  *
  * ==All three properties are emitted, including the optional one==
  *
  * `title` and `key` are the two `CreateKeyOption` marks required, so there was never a choice about those. `read_only`
  * is optional and is emitted anyway, which is a departure from the "only what the caller set" rule the rest of this
  * group follows. The reason is what the default is: `read_only` is a Go `bool`, so an omitted property creates a key
  * that '''may push'''. Sending the flag explicitly means the request body states the grant it is asking for, and a
  * reader of a captured request never has to know Forgejo's zero values to know what a key was given. See
  * [[com.worxbend.codeberg4s.repositories.access.CreateDeployKey]].
  *
  * The key material is public and is rendered in the clear, because that is what it is; see
  * [[com.worxbend.codeberg4s.repositories.access.DeployKey]].
  */
private[codeberg4s] object CreateKeyOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateDeployKey): String =
    Json.render(
      JsonValue.Obj(
        DeployKeyWire.Title    -> JsonValue.Str(command.title),
        DeployKeyWire.Key      -> JsonValue.Str(command.key),
        DeployKeyWire.ReadOnly -> JsonValue.Bool(command.isReadOnly),
      )
    )
