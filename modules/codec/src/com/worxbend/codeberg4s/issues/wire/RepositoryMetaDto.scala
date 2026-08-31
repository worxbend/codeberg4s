package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.repositories.RepoSlug
import com.worxbend.codeberg4s.{Owner, RepoName}

/** Forgejo's `RepositoryMeta` model — the four-key object an `Issue` carries under `repository`.
  *
  * '''This is not a reduced `Repository`, and it must not be decoded as one.''' `docs/LEDGER.md` says to widen an
  * existing DTO rather than fork it when the API embeds a smaller form of a model, and that rule does not apply here:
  * `RepositoryMeta.owner` is a bare login '''string''' (`"owner": "Codeberg"` on every issue in the fixtures), whereas
  * `Repository.owner` is a `User` object. Handing this payload to
  * [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]] would read `owner` as absent and then fail conversion on
  * a field that is right there in the JSON.
  *
  * It earns no domain model of its own either. Its `id` and `full_name` add nothing a caller cannot derive, so it is
  * projected straight to a [[com.worxbend.codeberg4s.repositories.RepoSlug]] — a foundation type that predates every
  * wave — and the issue holds that.
  */
final case class RepositoryMetaDto(
    id: Option[Long],
    name: Option[String],
    owner: Option[String],
    fullName: Option[String],
):

  /** The repository this points at, or `None` when it does not point at an addressable one.
    *
    * Deliberately total rather than an `Either`: `owner` and `name` both go through the smart constructors that reject
    * path-forging values, and a meta object that fails them costs the caller a slug, not the issue. An issue whose
    * `repository` is unusable is still a perfectly good issue — and on a single-repository listing the caller already
    * knows which repository they asked about.
    */
  def toSlug: Option[RepoSlug] =
    for
      handle <- owner.flatMap(value => Owner.from(value).toOption)
      repo   <- name.flatMap(value => RepoName.from(value).toOption)
    yield RepoSlug(handle, repo)

object RepositoryMetaDto:

  /** Reads a `RepositoryMeta` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[RepositoryMetaDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. Used by the reader above and by [[IssueDto]]. */
  def fromFields(fields: JsonFields): RepositoryMetaDto =
    RepositoryMetaDto(
      id       = fields.number("id"),
      name     = fields.text("name"),
      owner    = fields.text("owner"),
      fullName = fields.text("full_name"),
    )
