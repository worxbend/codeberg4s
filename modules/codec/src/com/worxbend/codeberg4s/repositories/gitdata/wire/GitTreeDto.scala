package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.GitTreeEntry

/** Forgejo's `GitTreeResponse` — the envelope `GET /repos/{owner}/{repo}/git/trees/{sha}` wraps its entries in.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' The six keys are the spec's.
  *
  * ==How the envelope's own paging fields are used==
  *
  * Forgejo sends no `Link` header for this endpoint, only `X-Total-Count` and the body's own `total_count`, so the
  * window cannot be assembled from `Link` alone — see [[com.worxbend.codeberg4s.core.Pages.fromEnvelope]] and
  * `docs/HAZARDS.md` §5. `total_count` is therefore handed to the page builder, which offers a next page while
  * `page × items.size < total`. `truncated` is still '''not''' used as an end-of-collection test: it is `true` on every
  * page of a multi-page tree, so it says only "there is more somewhere", not "there is a next page here".
  *
  * @param sha
  *   the `sha` key: the tree that was listed
  * @param url
  *   the `url` key
  * @param entries
  *   the `tree` key
  * @param page
  *   the `page` key, as the instance echoes it
  * @param totalCount
  *   the `total_count` key
  * @param truncated
  *   the `truncated` key
  */
final case class GitTreeDto(
    sha: Option[String],
    url: Option[String],
    entries: Vector[GitEntryDto],
    page: Option[Long],
    totalCount: Option[Long],
    truncated: Option[Boolean],
):

  /** Converts the envelope's entries, reporting each failure at its own index under `$.tree`.
    *
    * The envelope itself demands nothing: a tree with no entries is an empty directory, and a response whose `sha` the
    * instance omitted is still a usable list of entries.
    */
  def toDomain: Either[DecodeFailure, Vector[GitTreeEntry]] =
    ArrayElements.convert(JsonPath.Root.field("tree"), entries)((dto, path) => dto.toDomainAt(path))

object GitTreeDto:

  /** Reads a `GitTreeResponse` object. */
  given JsonDecoder[GitTreeDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): GitTreeDto =
    GitTreeDto(
      sha        = fields.text("sha"),
      url        = fields.text("url"),
      entries    = fields.nestedAll("tree").map(GitEntryDto.fromFields),
      page       = fields.number("page"),
      totalCount = fields.number("total_count"),
      truncated  = fields.boolean("truncated"),
    )
