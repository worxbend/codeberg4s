package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.gitdata.CombinedCommitStatus
import com.worxbend.codeberg4s.repositories.gitdata.CommitStatusState
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto

/** Forgejo's `CombinedStatus` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' `repository` is the same `Repository` object the
  * repository endpoints return, so [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]] is reused.
  *
  * @param sha
  *   the `sha` key: the commit the request's ref resolved to
  * @param state
  *   the `state` key: the instance's reduction of the individual verdicts
  * @param totalCount
  *   the `total_count` key
  * @param statuses
  *   the `statuses` key
  * @param repository
  *   the `repository` key
  * @param commitUrl
  *   the `commit_url` key
  * @param url
  *   the `url` key
  */
final case class CombinedStatusDto(
    sha: Option[String],
    state: Option[String],
    totalCount: Option[Long],
    statuses: Vector[CommitStatusDto],
    repository: Option[RepositoryDto],
    commitUrl: Option[String],
    url: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `sha`, through [[com.worxbend.codeberg4s.repositories.CommitSha.from]]: the request may have named a
    * branch, and the resolved commit is the only thing that says which commit was answered about. An unrecognised
    * `state` becomes `None` rather than a failure. A failure inside `statuses` or `repository` is reported at its own
    * path, index included.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, CombinedCommitStatus] =
    for
      commit   <- Wire.validated(at, "sha", sha)(CommitSha.from)
      reported <- ArrayElements.convert(at.field("statuses"), statuses)((dto, path) => dto.toDomainAt(path))
      repo     <- repositoryAt(at)
    yield CombinedCommitStatus(
      sha        = commit,
      state      = state.flatMap(CommitStatusState.parse),
      totalCount = totalCount.getOrElse(0L),
      statuses   = reported,
      repository = repo,
      commitUrl  = commitUrl,
      url        = url,
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, CombinedCommitStatus] =
    toDomainAt(JsonPath.Root)

  private def repositoryAt(at: JsonPath): Either[DecodeFailure, Option[Repository]] =
    repository.fold(Right(None))(dto => dto.toDomainAt(at.field("repository")).map(Some.apply))

object CombinedStatusDto:

  /** Reads a `CombinedStatus` object. */
  given JsonDecoder[CombinedStatusDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): CombinedStatusDto =
    CombinedStatusDto(
      sha        = fields.text("sha"),
      state      = fields.text("state"),
      totalCount = fields.number("total_count"),
      statuses   = fields.nestedAll("statuses").map(CommitStatusDto.fromFields),
      repository = fields.nested("repository").map(RepositoryDto.fromFields),
      commitUrl  = fields.text("commit_url"),
      url        = fields.text("url"),
    )
