package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.ServerRepositorySettings

/** Forgejo's `GeneralRepoSettings` model, field for field.
  *
  * All seven keys of `golden/misc/settings-repository.json` are represented, and every one is `Option` per rule 2 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]].
  *
  * @param mirrorsDisabled
  *   `mirrors_disabled`
  * @param httpGitDisabled
  *   `http_git_disabled`
  * @param migrationsDisabled
  *   `migrations_disabled`
  * @param starsDisabled
  *   `stars_disabled`
  * @param forksDisabled
  *   `forks_disabled`
  * @param timeTrackingDisabled
  *   `time_tracking_disabled`
  * @param lfsDisabled
  *   `lfs_disabled`
  */
final case class ServerRepositorySettingsDto(
    mirrorsDisabled: Option[Boolean],
    httpGitDisabled: Option[Boolean],
    migrationsDisabled: Option[Boolean],
    starsDisabled: Option[Boolean],
    forksDisabled: Option[Boolean],
    timeTrackingDisabled: Option[Boolean],
    lfsDisabled: Option[Boolean],
):

  /** Converts to the domain. Always a `Right`.
    *
    * An absent flag becomes `false`, that is "not disabled". Every one of the seven is a switch an administrator turns
    * on to '''remove''' a feature, so a version of Forgejo that predates a switch is a version where the feature was
    * never removable — which is what `false` says. Requiring the keys instead would make this endpoint fail against
    * exactly the older instances a capability probe exists to cope with.
    *
    * There is no `toDomainAt` here, unlike [[ServerApiSettingsDto]]: this model appears only as a whole response body,
    * never nested inside another one, and a path parameter no failure could ever use would be decoration. The `Either`
    * stays because [[com.worxbend.codeberg4s.core.Decode]] is composed the same way for every endpoint.
    */
  def toDomain: Either[DecodeFailure, ServerRepositorySettings] =
    Right(
      ServerRepositorySettings(
        mirrorsDisabled      = mirrorsDisabled.getOrElse(false),
        httpGitDisabled      = httpGitDisabled.getOrElse(false),
        migrationsDisabled   = migrationsDisabled.getOrElse(false),
        starsDisabled        = starsDisabled.getOrElse(false),
        forksDisabled        = forksDisabled.getOrElse(false),
        timeTrackingDisabled = timeTrackingDisabled.getOrElse(false),
        lfsDisabled          = lfsDisabled.getOrElse(false),
      )
    )

object ServerRepositorySettingsDto:

  /** Reads a `/settings/repository` body. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ServerRepositorySettingsDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ServerRepositorySettingsDto =
    ServerRepositorySettingsDto(
      mirrorsDisabled      = fields.boolean("mirrors_disabled"),
      httpGitDisabled      = fields.boolean("http_git_disabled"),
      migrationsDisabled   = fields.boolean("migrations_disabled"),
      starsDisabled        = fields.boolean("stars_disabled"),
      forksDisabled        = fields.boolean("forks_disabled"),
      timeTrackingDisabled = fields.boolean("time_tracking_disabled"),
      lfsDisabled          = fields.boolean("lfs_disabled"),
    )
