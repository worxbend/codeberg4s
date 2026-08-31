package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.ServerApiSettings

/** Forgejo's `GeneralAPISettings` model, field for field.
  *
  * All four keys of `golden/misc/settings-api.json` are represented. Every one is `Option` per rule 2 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]], even though the live payload carries all four: the spec declares
  * nothing `required` on any response model, and this endpoint is exactly the one where guessing a number would be
  * expensive — a fabricated `max_response_items` is a pagination bug rather than a missing field.
  *
  * @param maxResponseItems
  *   `max_response_items`, the silent `limit` clamp described in `docs/HAZARDS.md` §5
  * @param defaultPagingNum
  *   `default_paging_num`, the page size applied when a request omits `limit`
  * @param defaultGitTreesPerPage
  *   `default_git_trees_per_page`, which governs the tree endpoint and not the paged list endpoints
  * @param defaultMaxBlobSize
  *   `default_max_blob_size`, in bytes
  */
final case class ServerApiSettingsDto(
    maxResponseItems: Option[Long],
    defaultPagingNum: Option[Long],
    defaultGitTreesPerPage: Option[Long],
    defaultMaxBlobSize: Option[Long],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `max_response_items` and `default_paging_num` are required, because they are the answer to the question the call
    * asked: a caller reads this endpoint precisely to learn the two numbers, and defaulting either to `0` would hand
    * back a page size no endpoint would accept while looking like a successful call. The other two describe endpoints
    * whose behaviour a caller can discover without them, so they stay optional.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ServerApiSettings] =
    for
      maxItems <- Wire.required(at, "max_response_items", maxResponseItems)
      paging   <- Wire.required(at, "default_paging_num", defaultPagingNum)
    yield ServerApiSettings(
      maxResponseItems = maxItems,
      defaultPagingNum = paging,
      gitTreesPerPage  = defaultGitTreesPerPage,
      maxBlobSizeBytes = defaultMaxBlobSize,
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, ServerApiSettings] =
    toDomainAt(JsonPath.Root)

object ServerApiSettingsDto:

  /** Reads a `/settings/api` body. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ServerApiSettingsDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ServerApiSettingsDto =
    ServerApiSettingsDto(
      maxResponseItems       = fields.number("max_response_items"),
      defaultPagingNum       = fields.number("default_paging_num"),
      defaultGitTreesPerPage = fields.number("default_git_trees_per_page"),
      defaultMaxBlobSize     = fields.number("default_max_blob_size"),
    )
