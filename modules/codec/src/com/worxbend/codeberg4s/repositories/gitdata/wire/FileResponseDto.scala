package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.FileChange
import com.worxbend.codeberg4s.repositories.wire.{ContentEntryDto, VerificationDto}

/** Forgejo's `FileResponse` — what `POST /repos/{owner}/{repo}/diffpatch` answers with.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' `content` is the same `ContentsResponse` the contents
  * endpoint returns and `verification` the same `PayloadCommitVerification` a commit carries, so both are the
  * repository wave's DTOs rather than new ones.
  *
  * @param commit
  *   the `commit` key
  * @param content
  *   the `content` key, absent when the operation touched no single file
  * @param verification
  *   the `verification` key
  */
final case class FileResponseDto(
    commit: Option[FileCommitDto],
    content: Option[ContentEntryDto],
    verification: Option[VerificationDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Demands nothing of its own: a patch that applied cleanly but reports no single file is a success, and so is one
    * whose instance signs nothing. A failure inside `commit` or `content` is reported at that key's own path — those
    * two do have required fields, a commit id and an entry's name, path, sha and type.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, FileChange] =
    for
      written <- Wire.nested(at, "commit", commit)(_.toDomainAt(_))
      entry   <- Wire.nested(at, "content", content)(_.toDomainAt(_))
    yield FileChange(commit = written, content = entry, verification = verification.map(_.toDomain))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, FileChange] =
    toDomainAt(JsonPath.Root)

object FileResponseDto:

  /** Reads a `FileResponse` object. */
  given JsonDecoder[FileResponseDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): FileResponseDto =
    FileResponseDto(
      commit       = fields.nested("commit").map(FileCommitDto.fromFields),
      content      = fields.nested("content").map(ContentEntryDto.fromFields),
      verification = fields.nested("verification").map(VerificationDto.fromFields),
    )
