package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps}
import com.worxbend.codeberg4s.repositories.GitIdentity

/** The author or committer recorded in a Git object.
  *
  * Covers Forgejo's two spellings of the same idea. Inside a branch's `commit` the object is a `PayloadUser` with
  * `name`, `email` and `username`; inside a commit's `commit` it is a `CommitUser` with `name`, `email` and `date`.
  * Both are captured — `golden/repository/branches-list.json` and `golden/repository/commits-list.json` — and one DTO
  * reads both, because every field is optional anyway and a second DTO differing by one key would be exactly the
  * duplication `docs/LEDGER.md` forbids.
  *
  * @param name
  *   the `name` key
  * @param email
  *   the `email` key
  * @param username
  *   the `username` key, sent only where Forgejo matched an account
  * @param date
  *   the `date` key as a raw string; [[com.worxbend.codeberg4s.codec.Timestamps]] parses it during conversion
  */
final case class GitIdentityDto(
    name: Option[String],
    email: Option[String],
    username: Option[String],
    date: Option[String],
):

  /** Converts to the domain. Cannot fail: a Git identity has no field the domain insists on, and Forgejo sends `""` for
    * the username of an unmatched signer — which [[com.worxbend.codeberg4s.codec.JsonFields.text]] already folds into
    * absence.
    */
  def toDomain: GitIdentity =
    GitIdentity(name = name, email = email, username = username, date = Timestamps.parseOptional(date))

object GitIdentityDto:

  /** Reads an author, committer or signer object. */
  given JsonDecoder[GitIdentityDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the DTOs that embed this one. */
  def fromFields(fields: JsonFields): GitIdentityDto =
    GitIdentityDto(
      name     = fields.text("name"),
      email    = fields.text("email"),
      username = fields.text("username"),
      date     = fields.text("date"),
    )
