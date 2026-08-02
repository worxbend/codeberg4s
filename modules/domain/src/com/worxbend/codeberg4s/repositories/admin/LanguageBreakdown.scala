package com.worxbend.codeberg4s.repositories.admin

import java.time.Instant

/** How many bytes of each language a repository holds — Forgejo's `LanguageStatistics`.
  *
  * The wire shape is a bare JSON object whose keys are language names and whose values are byte counts, with no
  * enclosing envelope and no fixed key set: `{"Go": 1234567, "Scala": 8901}`. That is why this is a model of its own
  * rather than a `Map` handed straight to the caller — the map alone cannot say what the numbers mean, and the derived
  * questions ([[total]], [[dominant]]) are the ones callers actually ask.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.'''
  *
  * The counts are Forgejo's own linguist-style analysis of the default branch, not a byte count of the working tree:
  * vendored and generated paths are excluded, and a repository whose analysis has not run yet answers `{}`.
  *
  * @param bytes
  *   bytes per language name, exactly as the instance reported them
  */
final case class LanguageBreakdown(bytes: Map[String, Long]):

  /** Every counted byte, across all languages. `0` for a repository with no analysis. */
  def total: Long = bytes.values.sum

  /** The language with the most bytes, or `None` when nothing was counted.
    *
    * Ties are broken by name, so the answer is stable across calls rather than depending on map iteration order.
    */
  def dominant: Option[String] =
    bytes.toVector.sortBy((name, count) => (-count, name)).headOption.map((name, _) => name)

  /** Whether the instance counted nothing — an empty repository, or one whose analysis has not run. */
  def isEmpty: Boolean = bytes.isEmpty

object LanguageBreakdown:

  /** A repository with nothing counted. */
  val Empty: LanguageBreakdown = LanguageBreakdown(Map.empty)

/** A topic and how widely it is used, as `GET /topics/search` reports it — Forgejo's `TopicResponse`.
  *
  * Distinct from [[com.worxbend.codeberg4s.repositories.publishing.Topic]], which is a validated name a caller
  * '''sets''' on a repository. This is a name the instance already knows, with the instance's own metadata around it,
  * and it is read-only.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.'''
  *
  * @param id
  *   the topic's instance-wide identifier
  * @param name
  *   the topic's name, as the instance normalised it — lowercase, hyphens for spaces
  * @param repositoryCount
  *   how many repositories carry the topic
  * @param createdAt
  *   when the instance first saw it
  * @param updatedAt
  *   when its repository count last changed
  */
final case class TopicSummary(
    id: TopicId,
    name: String,
    repositoryCount: Long,
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
