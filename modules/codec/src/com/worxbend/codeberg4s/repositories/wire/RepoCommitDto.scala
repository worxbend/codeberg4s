package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.CommitDetails
import com.worxbend.codeberg4s.repositories.CommitRef

/** Forgejo's `RepoCommit` — the `commit` object nested inside a [[CommitDto]].
  *
  * The nesting is Forgejo's, not this library's: a commit response carries the instance's view at the top level and the
  * Git object underneath, and the two disagree about who the author is often enough that flattening them would lose
  * information. See [[com.worxbend.codeberg4s.repositories.Commit]].
  *
  * @param url
  *   the `url` key
  * @param author
  *   the `author` key, as Git records it
  * @param committer
  *   the `committer` key, as Git records it
  * @param message
  *   the `message` key
  * @param tree
  *   the `tree` key
  * @param verification
  *   the `verification` key
  */
final case class RepoCommitDto(
    url: Option[String],
    author: Option[GitIdentityDto],
    committer: Option[GitIdentityDto],
    message: Option[String],
    tree: Option[CommitMetaDto],
    verification: Option[VerificationDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires nothing of its own; the only way this fails is a `tree` whose `sha` is missing or not hexadecimal, which
    * is reported at `$.commit.tree.sha`.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, CommitDetails] =
    treeAt(at).map: treeRef =>
      CommitDetails(
        message      = message,
        url          = url,
        author       = author.map(_.toDomain),
        committer    = committer.map(_.toDomain),
        tree         = treeRef,
        verification = verification.map(_.toDomain),
      )

  private def treeAt(at: JsonPath): Either[DecodeFailure, Option[CommitRef]] =
    tree.fold(Right(None))(dto => dto.toDomainAt(at.field("tree")).map(Some.apply))

object RepoCommitDto:

  /** Reads a `RepoCommit` object. */
  given JsonDecoder[RepoCommitDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the commit DTO that embeds this one. */
  def fromFields(fields: JsonFields): RepoCommitDto =
    RepoCommitDto(
      url          = fields.text("url"),
      author       = fields.nested("author").map(GitIdentityDto.fromFields),
      committer    = fields.nested("committer").map(GitIdentityDto.fromFields),
      message      = fields.text("message"),
      tree         = fields.nested("tree").map(CommitMetaDto.fromFields),
      verification = fields.nested("verification").map(VerificationDto.fromFields),
    )
