package com.worxbend.codeberg4s.repositories.publishing.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.repositories.publishing.CreateRelease

/** Forgejo's `CreateReleaseOption` request model — the body of `POST /repos/{owner}/{repo}/releases`.
  *
  * An object rather than a case class with a `Writer`, as every request model in this library is: a request body is
  * only ever '''written''', so a case class in between would have to be built and then rendered while the interesting
  * decision — which keys appear at all — stayed in the rendering.
  *
  * Derived from `spec/swagger.v1.json`; there is no golden capture of a release request, only of the response.
  *
  * '''Only what the caller set is emitted.''' `tag_name` is the one property the spec marks required and is always
  * present. The three flags are emitted only when `true`, because `false` is what Forgejo assumes and asserting a
  * default is not the same as staying quiet about it.
  */
private[codeberg4s] object CreateReleaseOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateRelease): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: CreateRelease): List[(String, JsonValue)] =
    List(
      Some("tag_name" -> JsonValue.Str(command.tagName.value)),
      command.target.map(commitish => "target_commitish" -> JsonValue.Str(commitish)),
      command.name.map(title       => "name" -> JsonValue.Str(title)),
      command.body.map(notes       => "body" -> JsonValue.Str(notes)),
      Option.when(command.isDraft)("draft"                        -> JsonValue.Bool(true)),
      Option.when(command.isPrerelease)("prerelease"              -> JsonValue.Bool(true)),
      Option.when(command.hidesArchiveLinks)("hide_archive_links" -> JsonValue.Bool(true)),
    ).flatten
