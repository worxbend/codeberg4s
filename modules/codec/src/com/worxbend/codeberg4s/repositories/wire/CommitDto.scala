package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.Commit
import com.worxbend.codeberg4s.repositories.CommitDetails
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `Commit` model, field for field.
  *
  * Every key on the two elements of `golden/repository/commits-list.json` is represented. Note the two authorship
  * pairs: `author`/`committer` at this level are [[com.worxbend.codeberg4s.users.wire.UserDto]] accounts, while
  * `commit.author`/`commit.committer` are Git identities. Reusing `UserDto` here rather than inventing a reduced
  * variant is what `docs/LEDGER.md` requires.
  *
  * @param url
  *   the `url` key
  * @param sha
  *   the `sha` key
  * @param created
  *   the `created` key as a raw string
  * @param htmlUrl
  *   the `html_url` key
  * @param commit
  *   the `commit` key: the Git object
  * @param author
  *   the `author` key: the instance account, when Forgejo matched one
  * @param committer
  *   the `committer` key, same caveat
  * @param parents
  *   the `parents` key; empty for a root commit
  * @param files
  *   the `files` key; empty when the endpoint does not report them
  * @param stats
  *   the `stats` key
  */
final case class CommitDto(
    url: Option[String],
    sha: Option[String],
    created: Option[String],
    htmlUrl: Option[String],
    commit: Option[RepoCommitDto],
    author: Option[UserDto],
    committer: Option[UserDto],
    parents: Vector[CommitMetaDto],
    files: Vector[CommitAffectedFileDto],
    stats: Option[CommitStatsDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `sha`, through [[com.worxbend.codeberg4s.repositories.CommitSha.from]]: it is the commit's identity and
    * the argument every follow-up call takes. Everything else is optional, and every nested failure — an account
    * without a login, a parent without a sha, a file without a name — is reported at its own path, index included.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Commit] =
    for
      identifier    <- Wire.validated(at, "sha", sha)(CommitSha.from)
      details       <- detailsAt(at)
      authorUser    <- userAt(at, "author", author)
      committerUser <- userAt(at, "committer", committer)
      parentRefs    <- ArrayElements.convert(at.field("parents"), parents)((dto, path) => dto.toDomainAt(path))
      changed       <- ArrayElements.convert(at.field("files"), files)((dto, path) => dto.toDomainAt(path))
    yield Commit(
      sha       = identifier,
      url       = url,
      htmlUrl   = htmlUrl,
      created   = Timestamps.parseOptional(created),
      author    = authorUser,
      committer = committerUser,
      details   = details,
      parents   = parentRefs,
      files     = changed,
      stats     = stats.map(_.toDomain),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Commit] =
    toDomainAt(JsonPath.Root)

  private def detailsAt(at: JsonPath): Either[DecodeFailure, Option[CommitDetails]] =
    commit.fold(Right(None))(dto => dto.toDomainAt(at.field("commit")).map(Some.apply))

  private def userAt(at: JsonPath, field: String, dto: Option[UserDto]): Either[DecodeFailure, Option[User]] =
    dto.fold(Right(None))(user => user.toDomainAt(at.field(field)).map(Some.apply))

object CommitDto:

  /** Reads a `Commit` object. */
  given JsonDecoder[CommitDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): CommitDto =
    CommitDto(
      url       = fields.text("url"),
      sha       = fields.text("sha"),
      created   = fields.text("created"),
      htmlUrl   = fields.text("html_url"),
      commit    = fields.nested("commit").map(RepoCommitDto.fromFields),
      author    = fields.nested("author").map(UserDto.fromFields),
      committer = fields.nested("committer").map(UserDto.fromFields),
      parents   = fields.nestedAll("parents").map(CommitMetaDto.fromFields),
      files     = fields.nestedAll("files").map(CommitAffectedFileDto.fromFields),
      stats     = fields.nested("stats").map(CommitStatsDto.fromFields),
    )
