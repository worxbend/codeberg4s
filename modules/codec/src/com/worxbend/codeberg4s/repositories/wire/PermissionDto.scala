package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.repositories.RepositoryPermissions

/** Forgejo's `Permission` model — the `permissions` object embedded in a repository.
  *
  * @param admin
  *   the `admin` key
  * @param push
  *   the `push` key
  * @param pull
  *   the `pull` key
  */
final case class PermissionDto(admin: Option[Boolean], push: Option[Boolean], pull: Option[Boolean]):

  /** Converts to the domain. Cannot fail: an absent flag is a denied flag, which is the safe reading and the one
    * Forgejo itself uses when it omits the object entirely.
    */
  def toDomain: RepositoryPermissions =
    RepositoryPermissions(
      admin = admin.getOrElse(false),
      push  = push.getOrElse(false),
      pull  = pull.getOrElse(false),
    )

object PermissionDto:

  /** Reads a `permissions` object. */
  given upickle.default.Reader[PermissionDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the repository DTO that embeds this one. */
  def fromFields(fields: JsonFields): PermissionDto =
    PermissionDto(
      admin = fields.boolean("admin"),
      push  = fields.boolean("push"),
      pull  = fields.boolean("pull"),
    )
