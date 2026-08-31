package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.CommitComparison
import com.worxbend.codeberg4s.repositories.wire.{CommitAffectedFileDto, CommitDto}

/** Forgejo's `Compare` model — three keys, all of them arrays or counts.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' Both nested arrays are the repository wave's models:
  * `commits` holds the same `Commit` objects the commit listing returns and `files` the same `CommitAffectedFiles`.
  *
  * @param totalCommits
  *   the `total_commits` key: the instance's count of the whole difference, which is not `commits.size` when the
  *   comparison was truncated
  * @param commits
  *   the `commits` key
  * @param files
  *   the `files` key
  */
final case class CompareDto(
    totalCommits: Option[Long],
    commits: Vector[CommitDto],
    files: Vector[CommitAffectedFileDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Demands nothing of its own — comparing a ref with itself is a legitimate request whose answer is empty. A failure
    * inside either array is reported at that element's own index.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, CommitComparison] =
    for
      history <- ArrayElements.convert(at.field("commits"), commits)((dto, path) => dto.toDomainAt(path))
      changed <- ArrayElements.convert(at.field("files"), files)((dto, path) => dto.toDomainAt(path))
    yield CommitComparison(totalCommits = totalCommits.getOrElse(0L), commits = history, files = changed)

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, CommitComparison] =
    toDomainAt(JsonPath.Root)

object CompareDto:

  /** Reads a `Compare` object. */
  given JsonDecoder[CompareDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): CompareDto =
    CompareDto(
      totalCommits = fields.number("total_commits"),
      commits      = fields.nestedAll("commits").map(CommitDto.fromFields),
      files        = fields.nestedAll("files").map(CommitAffectedFileDto.fromFields),
    )
