package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.pulls.ChangedFile
import com.worxbend.codeberg4s.repositories.CommitFileStatus

/** Forgejo's `ChangedFile` model, field for field.
  *
  * Eight of the nine declared keys appear on the single element of `golden/pull/files-list.json`; the ninth,
  * `previous_filename`, is populated only for a rename and is absent there — which is the ordinary case and not a
  * missing capture.
  *
  * `status` stays a raw string here and is interpreted during conversion by
  * [[com.worxbend.codeberg4s.repositories.CommitFileStatus.parse]], the repository wave's enum. Reusing it rather than
  * declaring a second copy of the same seven cases is what `docs/LEDGER.md` requires; the vocabulary is Forgejo's own
  * rendering of Git's status letters and does not differ between a commit and a pull request.
  */
final case class ChangedFileDto(
    filename: Option[String],
    status: Option[String],
    additions: Option[Long],
    deletions: Option[Long],
    changes: Option[Long],
    previousFilename: Option[String],
    htmlUrl: Option[String],
    contentsUrl: Option[String],
    rawUrl: Option[String],
) extends WireModel[ChangedFile]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Only `filename` is required: a changed file with no path is not a changed file, and nothing else here can stand in
    * for it. The three counts default to `0` when absent, and `status` becomes `None` for a spelling this library does
    * not recognise rather than failing the file — see [[com.worxbend.codeberg4s.repositories.CommitFileStatus.parse]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ChangedFile] =
    Wire.required(at, "filename", filename).map: path =>
      ChangedFile(
        filename         = path,
        status           = status.flatMap(CommitFileStatus.parse),
        additions        = additions.getOrElse(0L),
        deletions        = deletions.getOrElse(0L),
        changes          = changes.getOrElse(0L),
        previousFilename = previousFilename,
        htmlUrl          = htmlUrl,
        contentsUrl      = contentsUrl,
        rawUrl           = rawUrl,
      )

object ChangedFileDto:

  /** Reads a `ChangedFile` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ChangedFileDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ChangedFileDto =
    ChangedFileDto(
      filename         = fields.text("filename"),
      status           = fields.text("status"),
      additions        = fields.number("additions"),
      deletions        = fields.number("deletions"),
      changes          = fields.number("changes"),
      previousFilename = fields.text("previous_filename"),
      htmlUrl          = fields.text("html_url"),
      contentsUrl      = fields.text("contents_url"),
      rawUrl           = fields.text("raw_url"),
    )
