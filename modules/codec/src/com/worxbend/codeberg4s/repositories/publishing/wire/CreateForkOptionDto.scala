package com.worxbend.codeberg4s.repositories.publishing.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.repositories.publishing.CreateFork

/** Forgejo's `CreateForkOption` request model — the body of `POST /repos/{owner}/{repo}/forks`.
  *
  * An object rather than a case class, for the reason [[CreateReleaseOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of this request exists.
  *
  * '''Only what the caller set is emitted.''' An [[com.worxbend.codeberg4s.repositories.publishing.CreateFork.Empty]]
  * renders as `{}` — the plain "fork it to me, same name" request, which is what the spec's all-optional body means.
  */
private[codeberg4s] object CreateForkOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateFork): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: CreateFork): List[(String, JsonValue)] =
    List(
      command.name.map(target      => "name" -> JsonValue.Str(target.value)),
      command.organization.map(org => "organization" -> JsonValue.Str(org.value)),
    ).flatten
