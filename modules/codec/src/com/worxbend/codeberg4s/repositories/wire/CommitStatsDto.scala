package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.repositories.CommitStats

/** Forgejo's `CommitStats` — the `stats` object on a commit.
  *
  * @param total
  *   the `total` key
  * @param additions
  *   the `additions` key
  * @param deletions
  *   the `deletions` key
  */
final case class CommitStatsDto(total: Option[Long], additions: Option[Long], deletions: Option[Long]):

  /** Converts to the domain. Cannot fail: an absent counter is zero, which is what an empty commit reports. */
  def toDomain: CommitStats =
    CommitStats(
      total     = total.getOrElse(0L),
      additions = additions.getOrElse(0L),
      deletions = deletions.getOrElse(0L),
    )

object CommitStatsDto:

  /** Reads a `stats` object. */
  given JsonDecoder[CommitStatsDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the commit DTO that embeds this one. */
  def fromFields(fields: JsonFields): CommitStatsDto =
    CommitStatsDto(
      total     = fields.number("total"),
      additions = fields.number("additions"),
      deletions = fields.number("deletions"),
    )
