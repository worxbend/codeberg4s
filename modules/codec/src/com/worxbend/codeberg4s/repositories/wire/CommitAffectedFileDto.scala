package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.{CommitFile, CommitFileStatus}

/** Forgejo's `CommitAffectedFiles` — one element of a commit's `files` array.
  *
  * @param filename
  *   the `filename` key
  * @param status
  *   the `status` key; every element of `golden/repository/commits-list.json` reports `modified`
  */
final case class CommitAffectedFileDto(filename: Option[String], status: Option[String]):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `filename`, because a changed file with no path says nothing. An unrecognised `status` becomes `None`
    * rather than a failure — see [[com.worxbend.codeberg4s.repositories.CommitFileStatus.parse]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, CommitFile] =
    Wire
      .required(at, "filename", filename)
      .map(path => CommitFile(filename = path, status = status.flatMap(CommitFileStatus.parse)))

object CommitAffectedFileDto:

  /** Reads one element of a `files` array. */
  given JsonDecoder[CommitAffectedFileDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the commit DTO that embeds these. */
  def fromFields(fields: JsonFields): CommitAffectedFileDto =
    CommitAffectedFileDto(
      filename = fields.text("filename"),
      status   = fields.text("status"),
    )
