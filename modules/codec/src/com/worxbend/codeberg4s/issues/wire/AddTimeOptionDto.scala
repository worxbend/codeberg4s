package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.WireValues
import com.worxbend.codeberg4s.issues.AddTrackedTime

/** Forgejo's `AddTimeOption` request model — the body of `POST /repos/{owner}/{repo}/issues/{index}/times`.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of this request exists.
  *
  * `time` is the model's one required property and is always emitted, as '''seconds''' — the unit the spec documents
  * and the reason [[com.worxbend.codeberg4s.issues.AddTrackedTime]] refuses a sub-second duration rather than letting
  * one be truncated here. `created` and `user_name` are emitted only when the caller set them, so an ordinary call
  * attributes the time to the token's own account at the instance's own clock.
  */
private[codeberg4s] object AddTimeOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: AddTrackedTime): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: AddTrackedTime): List[(String, JsonValue)] =
    List(
      Some("time" -> WireValues.whole(command.spent.toSeconds)),
      command.userName.map(login   => "user_name" -> JsonValue.Str(login)),
      command.createdAt.map(moment => "created" -> JsonValue.Str(Timestamps.render(moment))),
    ).flatten
