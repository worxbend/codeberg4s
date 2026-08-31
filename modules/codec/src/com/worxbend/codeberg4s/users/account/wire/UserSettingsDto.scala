package com.worxbend.codeberg4s.users.account.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.account.UserSettings

/** Forgejo's `UserSettings` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' `/user/settings` requires a token and the golden
  * harvest was anonymous, so no fixture exists for this shape. The text fields are nevertheless read with
  * [[com.worxbend.codeberg4s.codec.JsonFields.text]] rather than `rawText`, on the strength of evidence from a
  * neighbouring model: `golden/user/user-single.json` shows Forgejo sending `""` and not `null` for unset `location`,
  * `website`, `description` and `language` on the `User` model, and the settings payload is the same fields on the same
  * account.
  *
  * ==Nothing here is required==
  *
  * [[toDomainAt]] cannot fail. There is no identifier on this model — it describes the account the credentials belong
  * to, which the caller already knows — so there is no field whose absence would leave a value nobody can use. The
  * conversion still returns an `Either` because every `toDomain` in this library does, and because a decoder that can
  * only succeed today must not become a breaking change the day a field becomes load-bearing.
  */
final case class UserSettingsDto(
    fullName: Option[String],
    website: Option[String],
    location: Option[String],
    description: Option[String],
    pronouns: Option[String],
    language: Option[String],
    theme: Option[String],
    diffViewStyle: Option[String],
    hideEmail: Option[Boolean],
    hideActivity: Option[Boolean],
    hidePronouns: Option[Boolean],
    enableRepoUnitHints: Option[Boolean],
):

  /** Converts to the domain.
    *
    * '''There is no `toDomainAt` on this model''', unlike every other DTO in the library, because there is no path to
    * report a failure at: the conversion cannot fail, and the payload is only ever a whole response body — it appears
    * in no array and is nested in nothing. Adding a path parameter nothing could use would be a signature written for
    * symmetry rather than for a caller.
    *
    * The four flags default to `false` when absent, per the note on
    * [[com.worxbend.codeberg4s.users.account.UserSettings]].
    */
  def toDomain: Either[DecodeFailure, UserSettings] =
    Right(
      UserSettings(
        fullName           = fullName,
        website            = website,
        location           = location,
        description        = description,
        pronouns           = pronouns,
        language           = language,
        theme              = theme,
        diffViewStyle      = diffViewStyle,
        hidesEmail         = hideEmail.getOrElse(false),
        hidesActivity      = hideActivity.getOrElse(false),
        hidesPronouns      = hidePronouns.getOrElse(false),
        showsRepoUnitHints = enableRepoUnitHints.getOrElse(false),
      )
    )

object UserSettingsDto:

  /** Reads a `UserSettings` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[UserSettingsDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): UserSettingsDto =
    UserSettingsDto(
      fullName            = fields.text("full_name"),
      website             = fields.text("website"),
      location            = fields.text("location"),
      description         = fields.text("description"),
      pronouns            = fields.text("pronouns"),
      language            = fields.text("language"),
      theme               = fields.text("theme"),
      diffViewStyle       = fields.text("diff_view_style"),
      hideEmail           = fields.boolean("hide_email"),
      hideActivity        = fields.boolean("hide_activity"),
      hidePronouns        = fields.boolean("hide_pronouns"),
      enableRepoUnitHints = fields.boolean("enable_repo_unit_hints"),
    )
