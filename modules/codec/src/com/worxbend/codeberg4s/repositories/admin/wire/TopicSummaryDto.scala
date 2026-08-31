package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Timestamps, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.admin.{TopicId, TopicSummary}

/** Forgejo's `TopicResponse` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' All five declared properties are
  * represented. Note the wire name of the topic itself: it is `topic_name`, not `name`, which is the sort of spelling
  * rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] exists to keep in one place.
  *
  * @param id
  *   the `id` key
  * @param topicName
  *   the `topic_name` key
  * @param repoCount
  *   the `repo_count` key
  * @param created
  *   the `created` key as a raw string
  * @param updated
  *   the `updated` key as a raw string
  */
final case class TopicSummaryDto(
    id: Option[Long],
    topicName: Option[String],
    repoCount: Option[Long],
    created: Option[String],
    updated: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `id` and `topic_name`: a search result with no name is not a topic anybody can act on, and the id is what
    * keeps two same-named topics apart. `repo_count` absent becomes `0`, which is what the API would answer for a topic
    * no repository carries.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, TopicSummary] =
    for
      identifier <- Wire.validated(at, "id", id)(TopicId.from)
      name       <- Wire.required(at, "topic_name", topicName)
    yield TopicSummary(
      id              = identifier,
      name            = name,
      repositoryCount = repoCount.getOrElse(0L),
      createdAt       = Timestamps.parseOptional(created),
      updatedAt       = Timestamps.parseOptional(updated),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, TopicSummary] =
    toDomainAt(JsonPath.Root)

object TopicSummaryDto:

  /** Reads a `TopicResponse` object. Absent and `null` are the same thing for every field. */
  given JsonDecoder[TopicSummaryDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): TopicSummaryDto =
    TopicSummaryDto(
      id        = fields.number("id"),
      topicName = fields.text("topic_name"),
      repoCount = fields.number("repo_count"),
      created   = fields.text("created"),
      updated   = fields.text("updated"),
    )

  /** Converts a whole array, each element failing at its own index. */
  def toDomainAll(base: JsonPath, dtos: Vector[TopicSummaryDto]): Either[DecodeFailure, Vector[TopicSummary]] =
    ArrayElements.convert(base, dtos)((dto, at) => dto.toDomainAt(at))

/** The `{"topics": [...]}` envelope `GET /topics/search` answers with.
  *
  * A third envelope shape, alongside the bare array most listings use and the `{"ok", "data"}` of `/repos/search`.
  * `spec/swagger.v1.json` declares it inline on the operation — it has no name in `definitions` — under the title
  * `TopicSearchResults`, which is why this DTO exists rather than reusing
  * [[com.worxbend.codeberg4s.wire.SearchEnvelopeDto]].
  *
  * It is '''not''' the same envelope as `GET /repos/{owner}/{repo}/topics`, which answers `{"topics": ["name", …]}` —
  * an array of bare strings, read by [[com.worxbend.codeberg4s.repositories.wire.TopicNamesDto]]. Same key, different
  * element type, which is precisely why both are written down.
  *
  * @param entries
  *   the `topics` key; empty when it is absent, `null` or not an array
  */
final case class TopicSearchEnvelopeDto(entries: Vector[TopicSummaryDto])

object TopicSearchEnvelopeDto:

  /** The wire key the results sit under, so the decoder can report `$.topics[2].id` rather than `$[2].id`. */
  val EntriesKey: String = "topics"

  /** Reads a `TopicSearchResults` object. */
  given JsonDecoder[TopicSearchEnvelopeDto] =
    JsonFields.reader(fields =>
      TopicSearchEnvelopeDto(fields.nestedAll(EntriesKey).map(TopicSummaryDto.fromFields))
    )
