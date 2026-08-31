package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.repositories.admin.{ForkSyncInfo, IssuePinsAllowed}

/** Forgejo's `SyncForkInfo` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' All four declared properties are
  * represented.
  *
  * `base_commit` and `fork_commit` stay strings rather than going through
  * [[com.worxbend.codeberg4s.repositories.CommitSha.from]]: a fork whose branch does not exist upstream reports `""`
  * for one of them, and validating would turn a well-formed "there is nothing to compare against" response into a
  * decoding failure. [[com.worxbend.codeberg4s.codec.JsonFields.text]] folds the `""` into absence.
  *
  * @param allowed
  *   the `allowed` key
  * @param commitsBehind
  *   the `commits_behind` key
  * @param baseCommit
  *   the `base_commit` key
  * @param forkCommit
  *   the `fork_commit` key
  */
final case class SyncForkInfoDto(
    allowed: Option[Boolean],
    commitsBehind: Option[Long],
    baseCommit: Option[String],
    forkCommit: Option[String],
):

  /** Converts to the domain. Cannot fail.
    *
    * `allowed` absent is read as `false` and `commits_behind` absent as `0`, both of which are the conservative
    * reading: a response that does not say syncing is permitted is not evidence that it is, and a response that does
    * not say how far behind the fork is must not be reported as "behind by an unknown amount".
    */
  def toDomain: ForkSyncInfo =
    ForkSyncInfo(
      allowed       = allowed.getOrElse(false),
      commitsBehind = commitsBehind.getOrElse(0L),
      baseCommit    = baseCommit,
      forkCommit    = forkCommit,
    )

object SyncForkInfoDto:

  /** Reads a `SyncForkInfo` object. Absent and `null` are the same thing for every field. */
  given JsonDecoder[SyncForkInfoDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): SyncForkInfoDto =
    SyncForkInfoDto(
      allowed       = fields.boolean("allowed"),
      commitsBehind = fields.number("commits_behind"),
      baseCommit    = fields.text("base_commit"),
      forkCommit    = fields.text("fork_commit"),
    )

/** Forgejo's `NewIssuePinsAllowed` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' Both declared properties are represented.
  *
  * @param issues
  *   the `issues` key
  * @param pullRequests
  *   the `pull_requests` key
  */
final case class IssuePinsAllowedDto(issues: Option[Boolean], pullRequests: Option[Boolean]):

  /** Converts to the domain. Cannot fail.
    *
    * Either flag absent is read as `false`: this endpoint exists so a caller can avoid attempting a pin that would be
    * rejected, and a response that does not say a pin is permitted is not evidence that it is.
    */
  def toDomain: IssuePinsAllowed =
    IssuePinsAllowed(issues = issues.getOrElse(false), pullRequests = pullRequests.getOrElse(false))

object IssuePinsAllowedDto:

  /** Reads a `NewIssuePinsAllowed` object. Absent and `null` are the same thing for both fields. */
  given JsonDecoder[IssuePinsAllowedDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): IssuePinsAllowedDto =
    IssuePinsAllowedDto(issues = fields.boolean("issues"), pullRequests = fields.boolean("pull_requests"))
