package com.worxbend.codeberg4s.repositories.publishing.wire

import com.worxbend.codeberg4s.repositories.publishing.CreateTag

/** Forgejo's `CreateTagOption` request model — the body of `POST /repos/{owner}/{repo}/tags`.
  *
  * An object rather than a case class, for the reason [[CreateReleaseOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of a tag request exists.
  *
  * '''Only what the caller set is emitted.''' `message` is load-bearing by its absence: no `message` key is what makes
  * the created tag lightweight rather than annotated, so emitting `"message": ""` for a caller who said nothing would
  * change what gets created.
  */
private[codeberg4s] object CreateTagOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateTag): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: CreateTag): List[(String, ujson.Value)] =
    List(
      Some("tag_name" -> ujson.Str(command.tagName.value)),
      command.message.map(text     => "message" -> ujson.Str(text)),
      command.target.map(commitish => "target" -> ujson.Str(commitish)),
    ).flatten
