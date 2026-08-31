package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.GitTreeEntry

/** Forgejo's `GitTreeResponse` — the envelope `GET /repos/{owner}/{repo}/git/trees/{sha}` wraps its entries in.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' The six keys are the spec's.
  *
  * ==Why the envelope's own paging fields are not converted==
  *
  * `page`, `total_count` and `truncated` describe the window, and this library assembles a window from the response's
  * `Link` header and from nothing else — see [[com.worxbend.codeberg4s.core.Pages]] and `docs/HAZARDS.md` §5. Keeping
  * them here, unconverted, records what the wire carries without letting a second end-of-collection test into the
  * library: `truncated` is the body's version of exactly the claim `items.size` makes, and the reason that claim is
  * refused is that Forgejo clamps a requested limit while echoing it back.
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
