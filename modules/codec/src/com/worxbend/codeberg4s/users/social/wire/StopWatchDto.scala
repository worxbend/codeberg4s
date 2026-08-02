package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.RepoSlug
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.users.social.StopWatch

import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration

/** Forgejo's `StopWatch` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''': `/user/stopwatches` requires a token and
  * the golden harvest was anonymous. All seven declared properties are represented and all are `Option`, per rule 2 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]].
  *
  * ==Two spellings of the same elapsed time==
  *
  * `seconds` is an `int64` and `duration` is Forgejo's own pre-formatted rendering of it, such as `"1h2m3s"`. The
  * domain keeps both — the first as a `scala.concurrent.duration.FiniteDuration` converted here, the second verbatim —
  * because the string is what the instance's UI shows and re-deriving it would mean guessing at Forgejo's rounding.
  *
  * ==The repository is two loose strings on the wire==
  *
  * `repo_owner_name` and `repo_name` are separate keys rather than a nested object, so they are read as strings here
  * and paired into a [[com.worxbend.codeberg4s.repositories.RepoSlug]] in conversion.
  *
  * @param created
  *   the `created` key as a raw string
  * @param duration
  *   the `duration` key
  * @param issueIndex
  *   the `issue_index` key
  * @param issueTitle
  *   the `issue_title` key
  * @param repoName
  *   the `repo_name` key
  * @param repoOwnerName
  *   the `repo_owner_name` key
  * @param seconds
  *   the `seconds` key
  */
final case class StopWatchDto(
    created: Option[String],
    duration: Option[String],
    issueIndex: Option[Long],
    issueTitle: Option[String],
    repoName: Option[String],
    repoOwnerName: Option[String],
    seconds: Option[Long],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `issue_index` is the one required field. A stopwatch exists to be stopped, stopping it means calling an
    * issue-scoped endpoint, and without the issue's number there is nothing to call — an entry missing it is reported
    * at `$.issue_index` rather than handed over as a timer nobody can act on.
    *
    * '''An absent `seconds` reads as zero.''' Unlike [[com.worxbend.codeberg4s.issues.wire.TrackedTimeDto]], where a
    * missing duration would corrupt a total the caller computes, a stopwatch's elapsed time is a snapshot that is stale
    * on arrival; treating an absent one as "no time recorded yet" is the reading that matches a timer just started, and
    * it is what keeps a page of stopwatches from failing over a field the spec never promised.
    *
    * '''The repository is lenient.''' `repo_owner_name` and `repo_name` go through
    * [[com.worxbend.codeberg4s.repositories.Owner.from]] and [[com.worxbend.codeberg4s.repositories.RepoName.from]],
    * and a pair either of them rejects becomes `None` rather than failing the entry — the same trade
    * [[com.worxbend.codeberg4s.issues.wire.RepositoryMetaDto.toSlug]] makes. The issue number survives, which is the
    * part a caller acts on.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, StopWatch] =
    Wire
      .required(at, "issue_index", issueIndex)
      .map: index =>
        StopWatch(
          issueIndex   = index,
          issueTitle   = issueTitle,
          repository   = slug,
          elapsed      = StopWatchDto.asDuration(seconds.getOrElse(0L)),
          durationText = duration,
          createdAt    = Timestamps.parseOptional(created),
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, StopWatch] =
    toDomainAt(JsonPath.Root)

  private def slug: Option[RepoSlug] =
    for
      handle <- repoOwnerName.flatMap(value => Owner.from(value).toOption)
      repo   <- repoName.flatMap(value => RepoName.from(value).toOption)
    yield RepoSlug(handle, repo)

object StopWatchDto:

  /** Reads a `StopWatch` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given upickle.default.Reader[StopWatchDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): StopWatchDto =
    StopWatchDto(
      created       = fields.text("created"),
      duration      = fields.text("duration"),
      issueIndex    = fields.number("issue_index"),
      issueTitle    = fields.text("issue_title"),
      repoName      = fields.text("repo_name"),
      repoOwnerName = fields.text("repo_owner_name"),
      seconds       = fields.number("seconds"),
    )

  /** Converts a decoded array of stopwatches, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[StopWatchDto]): Either[DecodeFailure, Vector[StopWatch]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))

  /** The wire's seconds as a duration. One line, in one place, so the unit is never re-derived. */
  def asDuration(seconds: Long): FiniteDuration =
    seconds.seconds
