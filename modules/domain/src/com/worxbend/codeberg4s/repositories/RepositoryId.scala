package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.{PositiveId, ValidationError}

/** The instance-wide identifier of a repository — the `{id}` of `GET /repositories/{id}`.
  *
  * The one way to address a repository that survives a rename or a transfer. `owner/name` does not: renaming a
  * repository or moving it to another owner changes both halves of the slug while this stays put, which is exactly why
  * Forgejo offers the endpoint at all.
  *
  * It lives beside [[Repository]] rather than under `repositories.admin` because [[Repository.id]] is one, and a model
  * every endpoint group hands back cannot depend on a type declared inside one of them.
  */
opaque type RepositoryId = Long

object RepositoryId:

  /** Parses a repository identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"repositoryId"` field
    */
  def from(value: Long): Either[ValidationError, RepositoryId] =
    PositiveId.from("repositoryId", value)

  extension (id: RepositoryId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id
