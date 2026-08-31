package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.GitNote
import com.worxbend.codeberg4s.repositories.wire.CommitDto

/** Forgejo's `Note` model — two keys, a message and the commit it hangs off.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' `commit` is the same `Commit` object the commit
  * endpoints return, so [[com.worxbend.codeberg4s.repositories.wire.CommitDto]] is reused rather than reduced.
  *
  * @param message
  *   the `message` key
  * @param commit
  *   the `commit` key
  */
final case class NoteDto(message: Option[String], commit: Option[CommitDto]):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Demands nothing of its own: a note is a message and the commit it is attached to, and the endpoint already
    * answered `404` if there was no note. A failure inside `commit` — a commit with no `sha`, say — is reported at
    * `commit`'s own path and does fail the conversion, because a commit that cannot be identified is not a commit.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, GitNote] =
    Wire.nested(at, "commit", commit)(_.toDomainAt(_)).map(target => GitNote(message = message, commit = target))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, GitNote] =
    toDomainAt(JsonPath.Root)

object NoteDto:

  /** Reads a `Note` object. */
  given JsonDecoder[NoteDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): NoteDto =
    NoteDto(
      message = fields.text("message"),
      commit  = fields.nested("commit").map(CommitDto.fromFields),
    )
