package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.RepoSlug

/** The `repository` object embedded in an `ActionRun`, read down to the two things a caller needs from it.
  *
  * ==Why not decode it as a `Repository`==
  *
  * The spec says this field is a full `Repository`, and [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]]
  * could read it. Handing it over would be wrong here for two reasons. First, that DTO's conversion '''fails''' when a
  * required field is missing, so a run whose embedded repository object is reduced — which is what every other embedded
  * model in this API turns out to be — would cost the caller the whole run rather than a slug. Second, a run listing
  * carries one of these per run, and materialising a full repository model per element is a cost nobody asked for. The
  * pattern is [[com.worxbend.codeberg4s.issues.wire.RepositoryMetaDto]]'s, and so is the conclusion: project to a
  * [[com.worxbend.codeberg4s.repositories.RepoSlug]] and keep the rest on the wire.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]].
  *
  * @param ownerLogin
  *   `owner.login` of the embedded object. Unlike `RepositoryMeta`, whose `owner` is a bare string, a `Repository`'s
  *   `owner` is a `User`, which is why this is read from a nested field
  * @param fullName
  *   the `owner/name` rendering, used only as a fallback when the nested owner is absent
  */
final case class ActionRepoRefDto(
    name: Option[String],
    ownerLogin: Option[String],
    fullName: Option[String],
):

  /** The repository this points at, or `None` when it does not point at an addressable one.
    *
    * Deliberately total rather than an `Either`, for the reason
    * [[com.worxbend.codeberg4s.issues.wire.RepositoryMetaDto.toSlug]] gives: both halves go through smart constructors
    * that reject path-forging values, and an object that fails them costs the caller a slug, not the run.
    *
    * `owner.login` and `name` are preferred over `full_name` because they are the values the endpoints actually take;
    * `full_name` is split on its single `/` only when the nested owner is missing, and a value with any other number of
    * slashes is rejected rather than guessed at.
    */
  def toSlug: Option[RepoSlug] =
    slugFromParts.orElse(slugFromFullName)

  private def slugFromParts: Option[RepoSlug] =
    for
      handle <- ownerLogin
      repo   <- name
      slug   <- ActionRepoRefDto.slugOf(handle, repo)
    yield slug

  private def slugFromFullName: Option[RepoSlug] =
    fullName.map(_.split('/')).flatMap:
      case Array(handle, repo) => ActionRepoRefDto.slugOf(handle, repo)
      case _                   => None

object ActionRepoRefDto:

  /** Reads the embedded repository object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ActionRepoRefDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ActionRepoRefDto =
    ActionRepoRefDto(
      name       = fields.text("name"),
      ownerLogin = fields.nested("owner").flatMap(_.text("login")),
      fullName   = fields.text("full_name"),
    )

  /** The slug for an owner/name pair, or `None` when either half is not an addressable path segment. */
  private def slugOf(handle: String, repo: String): Option[RepoSlug] =
    for
      validHandle <- Owner.from(handle).toOption
      validRepo   <- RepoName.from(repo).toOption
    yield RepoSlug(validHandle, validRepo)
