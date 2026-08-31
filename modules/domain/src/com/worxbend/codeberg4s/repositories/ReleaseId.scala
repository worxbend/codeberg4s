package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.{PositiveId, ValidationError}

/** The instance-local identifier of a release, as `GET /repos/{owner}/{repo}/releases/{id}` takes it.
  *
  * A release is addressed by this number rather than by its tag, and both are `Long`-shaped on the wire, so a bare
  * `Long` would let a caller pass an asset id or a repository id and get a plausible `404` instead of a compile error.
  */
opaque type ReleaseId = Long

object ReleaseId:

  /** Parses a release identifier.
    *
    * Rejects zero and negatives: Forgejo's identifiers are database row ids and start at one.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"releaseId"` field
    */
  def from(value: Long): Either[ValidationError, ReleaseId] =
    PositiveId.from("releaseId", value)

  extension (id: ReleaseId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id
