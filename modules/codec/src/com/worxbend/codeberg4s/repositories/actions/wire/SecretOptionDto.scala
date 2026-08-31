package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.repositories.actions.SecretValue

/** Forgejo's `CreateOrUpdateSecretOption` request model — the body of
  * `PUT /repos/{owner}/{repo}/actions/secrets/{secretname}`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives: a request model is a rendering, not a value anyone holds.
  *
  * ==This is the only place a secret's material is written down==
  *
  * [[com.worxbend.codeberg4s.repositories.actions.SecretValue.reveal]] is called here and nowhere else in the library.
  * What comes out is handed straight to `Json.render`, which escapes it into the request body — so a value containing a
  * newline, a quote or a backslash survives intact and cannot break out of the JSON string. The rendered body is then a
  * [[com.worxbend.codeberg4s.core.RequestBody.Json]], which the pipeline never copies into a
  * [[com.worxbend.codeberg4s.CallContext]] or an error.
  *
  * `data` is the model's single required property and is always emitted. There is nothing optional to omit.
  */
private[codeberg4s] object SecretOptionDto:

  /** The wire key the secret's material is sent under. Note that it is `data` here and `value` on the variable
    * endpoints — see [[ActionVariableDto]] for the full spelling table.
    */
  val DataKey: String = "data"

  /** Renders `value` as the JSON body to `PUT`.
    *
    * The returned string contains the secret in the clear, because that is what has to reach the instance. It is
    * consumed immediately by the request builder and is never logged, never stored and never put in a failure.
    */
  def render(value: SecretValue): String =
    Json.render(JsonValue.Obj(DataKey -> JsonValue.Str(value.reveal)))
