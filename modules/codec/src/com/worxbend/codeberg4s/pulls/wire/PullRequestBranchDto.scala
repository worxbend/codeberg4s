package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.pulls.PullRequestBranch
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto

/** Forgejo's `PRBranchInfo` model, field for field — the `base` and `head` objects of a pull request.
  *
  * All five declared keys appear on both ends of all five pull requests in `golden/pull/single-open.json`,
  * `single-merged.json`, `list-all.json` and `list-closed.json`.
  *
  * `repo` is a '''complete''' [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]], not a reduced one — on
  * `golden/pull/single-open.json` the head repository is the fork `trim21/forgejo` carrying its own populated `parent`
  * — so this DTO reuses the repository wave's DTO per `docs/LEDGER.md`. Note that this is the opposite situation from
  * an issue's `repository` key, which is Forgejo's four-key `RepositoryMeta` and must go through
  * [[com.worxbend.codeberg4s.issues.wire.RepositoryMetaDto]] instead; the two objects have the same name in prose and
  * different shapes on the wire.
  *
  * @param label
  *   the `label` key
  * @param ref
  *   the `ref` key as a raw string
  * @param sha
  *   the `sha` key as a raw string
  * @param repoId
  *   the `repo_id` key
  * @param repo
  *   the `repo` key
  */
final case class PullRequestBranchDto(
    label: Option[String],
    ref: Option[String],
    sha: Option[String],
    repoId: Option[Long],
    repo: Option[RepositoryDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires nothing of its own: a pull request whose `base` object carries only a label is still a pull request, and
    * failing the whole payload over it would serve nobody.
    *
    * The two Git values are treated differently, on purpose. `sha` is '''strict''' — present-but-not-hexadecimal is
    * reported at `$.head.sha`, because an object id that is not an object id means the payload is not what it claims.
    * `ref` is '''lenient''' — a value [[com.worxbend.codeberg4s.repositories.BranchName]] refuses becomes `None` —
    * because Forgejo puts genuine non-branch references there once the head branch has been deleted, and
    * `golden/pull/list-closed.json` shows exactly that with `refs/pull/13726/head`.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, PullRequestBranch] =
    for
      tip        <- Wire.optional(at, "sha", sha)(CommitSha.from)
      repository <- Wire.nested(at, "repo", repo)(_.toDomainAt(_))
    yield PullRequestBranch(
      label        = label,
      ref          = ref.flatMap(value => BranchName.from(value).toOption),
      sha          = tip,
      repositoryId = repoId,
      repository   = repository,
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, PullRequestBranch] =
    toDomainAt(JsonPath.Root)

object PullRequestBranchDto:

  /** Reads a `PRBranchInfo` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[PullRequestBranchDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto.fromFields]]
    * so no field spelling is written twice.
    */
  def fromFields(fields: JsonFields): PullRequestBranchDto =
    PullRequestBranchDto(
      label  = fields.text("label"),
      ref    = fields.text("ref"),
      sha    = fields.text("sha"),
      repoId = fields.number("repo_id"),
      repo   = fields.nested("repo").map(RepositoryDto.fromFields),
    )
