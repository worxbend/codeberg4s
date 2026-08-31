package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.admin.FileChangeSet
import com.worxbend.codeberg4s.repositories.gitdata.wire.FileCommitDto
import com.worxbend.codeberg4s.repositories.wire.{ContentEntryDto, VerificationDto}

/** Forgejo's `FilesResponse` — what `POST /repos/{owner}/{repo}/contents` answers with.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' The multi-file sibling of
  * [[com.worxbend.codeberg4s.repositories.gitdata.wire.FileResponseDto]]: same `commit` and `verification` keys, and
  * `files` — an array of the same `ContentsResponse` — where that one has a single `content`. The embedded DTOs are
  * therefore the repository wave's, not new ones.
  *
  * @param commit
  *   the `commit` key
  * @param files
  *   the `files` key; empty when it is absent, `null` or not an array
  * @param verification
  *   the `verification` key
  */
final case class FilesResponseDto(
    commit: Option[FileCommitDto],
    files: Vector[ContentEntryDto],
    verification: Option[VerificationDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Demands nothing of its own: a batch that Forgejo accepted is a success whether or not the instance describes the
    * commit back, and whether or not it signs anything. A failure inside `commit` or inside one element of `files` is
    * reported at that element's own path — `$.files[1].sha` — because those do have required fields.
    *
    * A batch of deletes legitimately produces an empty `files`, so an empty array is not treated as suspicious.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, FileChangeSet] =
    for
      written <- Wire.nested(at, "commit", commit)(_.toDomainAt(_))
      entries <- ArrayElements.convert(at.field("files"), files)((dto, path) => dto.toDomainAt(path))
    yield FileChangeSet(commit = written, files = entries, verification = verification.map(_.toDomain))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, FileChangeSet] =
    toDomainAt(JsonPath.Root)

object FilesResponseDto:

  /** Reads a `FilesResponse` object. */
  given JsonDecoder[FilesResponseDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): FilesResponseDto =
    FilesResponseDto(
      commit       = fields.nested("commit").map(FileCommitDto.fromFields),
      files        = fields.nestedAll("files").map(ContentEntryDto.fromFields),
      verification = fields.nested("verification").map(VerificationDto.fromFields),
    )
