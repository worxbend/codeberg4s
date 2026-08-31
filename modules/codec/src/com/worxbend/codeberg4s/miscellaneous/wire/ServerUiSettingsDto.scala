package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.ServerUiSettings

/** Forgejo's `GeneralUISettings` model — the body of `GET /settings/ui`.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' The other three `/settings` endpoints have
  * golden fixtures; this one was not harvested, so the three fields below are the spec read literally.
  *
  * @param allowedReactions
  *   `allowed_reactions`, the emoji the reaction endpoints accept
  * @param customEmojis
  *   `custom_emojis`, the names this deployment defines beyond the standard set
  * @param defaultTheme
  *   `default_theme`, what a signed-out visitor sees
  */
final case class ServerUiSettingsDto(
    allowedReactions: Vector[String],
    customEmojis: Vector[String],
    defaultTheme: Option[String],
):

  /** Converts to the domain. Always a `Right`.
    *
    * '''Cannot fail on a JSON object''', exactly as [[ServerRepositorySettingsDto]] cannot: no field is required, the
    * two arrays default to empty and the theme to absent, so only a body that is not an object at all fails — and that
    * failure is the parser's, raised before this method is reached.
    *
    * There is no `toDomainAt` here, for the reason [[ServerRepositorySettingsDto.toDomain]] gives: this model appears
    * only as a whole response body, never nested inside another one. The `Either` stays because
    * [[com.worxbend.codeberg4s.core.Decode]] is composed the same way for every endpoint.
    */
  def toDomain: Either[DecodeFailure, ServerUiSettings] =
    Right(
      ServerUiSettings(
        allowedReactions = allowedReactions,
        customEmojis     = customEmojis,
        defaultTheme     = defaultTheme,
      )
    )

object ServerUiSettingsDto:

  /** Reads a `/settings/ui` body. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ServerUiSettingsDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ServerUiSettingsDto =
    ServerUiSettingsDto(
      allowedReactions = fields.texts("allowed_reactions"),
      customEmojis     = fields.texts("custom_emojis"),
      defaultTheme     = fields.text("default_theme"),
    )
