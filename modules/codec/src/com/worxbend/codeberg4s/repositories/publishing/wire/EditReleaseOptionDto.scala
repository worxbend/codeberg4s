package com.worxbend.codeberg4s.repositories.publishing.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.repositories.publishing.EditRelease

/** Forgejo's `EditReleaseOption` request model — the body of `PATCH /repos/{owner}/{repo}/releases/{id}`.
  *
  * An object rather than a case class, for the reason [[CreateReleaseOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of a release request exists.
  *
  * '''Only what the caller set is emitted, and here that is the entire semantics of the request.''' A `PATCH` leaves
  * alone whatever the body does not mention. The three flags are `Option[Boolean]` on the command and are emitted with
  * whatever value the caller stated, `false` included — unlike [[CreateReleaseOptionDto]], where `false` is silence.
  * Publishing a draft '''is''' `"draft": false`, so a renderer that dropped falses here would make the operation
  * impossible to express.
  *
  * An [[com.worxbend.codeberg4s.repositories.publishing.EditRelease.Empty]] renders as `{}`, a well-formed request that
  * changes nothing.
  */
private[codeberg4s] object EditReleaseOptionDto:

  /** Renders `command` as the JSON body to `PATCH`. */
  def render(command: EditRelease): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: EditRelease): List[(String, JsonValue)] =
    List(
      command.tagName.map(tag            => "tag_name" -> JsonValue.Str(tag.value)),
      command.target.map(commitish       => "target_commitish" -> JsonValue.Str(commitish)),
      command.name.map(title             => "name" -> JsonValue.Str(title)),
      command.body.map(notes             => "body" -> JsonValue.Str(notes)),
      command.isDraft.map(flag           => "draft" -> JsonValue.Bool(flag)),
      command.isPrerelease.map(flag      => "prerelease" -> JsonValue.Bool(flag)),
      command.hidesArchiveLinks.map(flag => "hide_archive_links" -> JsonValue.Bool(flag)),
    ).flatten
