package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.CommitSummary

/** Forgejo's `PayloadCommit` — the `commit` object a branch carries.
  *
  * Not the same model as [[CommitDto]], however similar it reads: this one identifies the commit as `id`, its author
  * and committer are Git identities rather than accounts, and it carries `added`, `removed` and `modified` file lists
  * instead of `files` and `stats`. Those three arrive as JSON `null` on both branch captures, which is exactly the
  * shape [[com.worxbend.codeberg4s.codec.JsonFields]] exists to absorb — a derived codec would abort on them.
  *
  * @param id
  *   the `id` key: the commit's object id
  * @param message
  *   the `message` key
  * @param url
  *   the `url` key
  * @param author
  *   the `author` key
  * @param committer
  *   the `committer` key
  * @param verification
  *   the `verification` key
  * @param timestamp
  *   the `timestamp` key as a raw string
  * @param added
  *   the `added` key; empty when the instance sends `null`, which it does on both captures
  * @param removed
  *   the `removed` key, same caveat
  * @param modified
  *   the `modified` key, same caveat
  */
final case class PayloadCommitDto(
    id: Option[String],
    message: Option[String],
    url: Option[String],
    author: Option[GitIdentityDto],
    committer: Option[GitIdentityDto],
    verification: Option[VerificationDto],
    timestamp: Option[String],
    added: Vector[String],
    removed: Vector[String],
    modified: Vector[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Fails only on `id`, which is both the commit's identity and a value
    * [[com.worxbend.codeberg4s.repositories.CommitSha.from]] validates as hexadecimal. The three file lists have no
    * domain counterpart: they describe a webhook payload's diff and are always `null` on the branch endpoints, so
    * carrying them into [[com.worxbend.codeberg4s.repositories.CommitSummary]] would promise data that never arrives.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, CommitSummary] =
    Wire
      .validated(at, "id", id)(CommitSha.from)
      .map: sha =>
        CommitSummary(
          sha          = sha,
          message      = message,
          url          = url,
          author       = author.map(_.toDomain),
          committer    = committer.map(_.toDomain),
          verification = verification.map(_.toDomain),
          timestamp    = Timestamps.parseOptional(timestamp),
        )

object PayloadCommitDto:

  /** Reads a `PayloadCommit` object. */
  given JsonDecoder[PayloadCommitDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the branch DTO that embeds this one. */
  def fromFields(fields: JsonFields): PayloadCommitDto =
    PayloadCommitDto(
      id           = fields.text("id"),
      message      = fields.text("message"),
      url          = fields.text("url"),
      author       = fields.nested("author").map(GitIdentityDto.fromFields),
      committer    = fields.nested("committer").map(GitIdentityDto.fromFields),
      verification = fields.nested("verification").map(VerificationDto.fromFields),
      timestamp    = fields.text("timestamp"),
      added        = fields.texts("added"),
      removed      = fields.texts("removed"),
      modified     = fields.texts("modified"),
    )
