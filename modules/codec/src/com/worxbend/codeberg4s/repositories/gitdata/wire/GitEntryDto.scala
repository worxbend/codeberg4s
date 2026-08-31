package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.{GitObjectKind, GitTreeEntry}
import com.worxbend.codeberg4s.repositories.{CommitSha, ContentPath}

/** Forgejo's `GitEntry` — one element of a tree listing's `tree` array.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' The tree endpoint has no golden fixture; these six
  * keys are the spec's.
  *
  * @param path
  *   the `path` key, relative to the tree that was requested
  * @param mode
  *   the `mode` key: the octal file mode as text, leading zero included
  * @param entryType
  *   the `type` key, renamed because `type` is a Scala keyword
  * @param size
  *   the `size` key, in bytes; `0` for a tree
  * @param sha
  *   the `sha` key: the id of the entry's own object
  * @param url
  *   the `url` key
  */
final case class GitEntryDto(
    path: Option[String],
    mode: Option[String],
    entryType: Option[String],
    size: Option[Long],
    sha: Option[String],
    url: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `path` and `sha`, both through their smart constructors, because both are used to build the next request
    * and neither may forge one. An unrecognised `type` becomes `None` rather than a failure: a tree page is large, and
    * one entry naming an object kind this library does not know must not cost the caller the other four hundred.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, GitTreeEntry] =
    for
      entryPath <- Wire.validated(at, "path", path)(ContentPath.from)
      objectId  <- Wire.validated(at, "sha", sha)(CommitSha.from)
    yield GitTreeEntry(
      path = entryPath,
      sha  = objectId,
      kind = entryType.flatMap(GitObjectKind.parse),
      mode = mode,
      size = size.getOrElse(0L),
      url  = url,
    )

object GitEntryDto:

  /** Reads one element of a `tree` array. */
  given JsonDecoder[GitEntryDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the envelope DTO that embeds these. */
  def fromFields(fields: JsonFields): GitEntryDto =
    GitEntryDto(
      path      = fields.text("path"),
      mode      = fields.text("mode"),
      entryType = fields.text("type"),
      size      = fields.number("size"),
      sha       = fields.text("sha"),
      url       = fields.text("url"),
    )
