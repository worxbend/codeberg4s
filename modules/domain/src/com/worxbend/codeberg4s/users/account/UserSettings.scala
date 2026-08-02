package com.worxbend.codeberg4s.users.account

/** The authenticated account's profile and privacy settings, as `GET /user/settings` reports them.
  *
  * '''Derived from `spec/swagger.v1.json`'s `UserSettings` definition, not from a captured response'''; see
  * [[OAuth2Application]] for what that means and why no fixture exists.
  *
  * ==Text fields are `Option`, flags are not==
  *
  * Forgejo returns `""` rather than `null` for unset text — `golden/user/user-single.json` shows `location`, `website`,
  * `description` and `language` all as empty strings on a real account — and
  * [[com.worxbend.codeberg4s.codec.JsonFields.text]] folds that spelling into absence, so an unset field arrives here
  * as `None` whichever way the instance spelled it. The four booleans take the reading [[Email]] documents: an absent
  * flag is `false`, because that is the value every caller would have supplied themselves.
  *
  * ==`theme` and `diffViewStyle` stay strings==
  *
  * Both are instance configuration: a Forgejo deployment ships whatever themes it was built with, and the diff view
  * vocabulary has changed across releases. The spec enumerates neither. An `enum` here would be a list this library
  * invented, and a value a newer instance sent would be one it dropped — so they are reported verbatim.
  *
  * @param fullName
  *   the display name shown beside the login
  * @param website
  *   the profile link
  * @param location
  *   the free-text location
  * @param description
  *   the profile biography
  * @param pronouns
  *   the pronouns shown on the profile, subject to [[hidesPronouns]]
  * @param language
  *   the interface language tag the account chose, such as `en-US`
  * @param theme
  *   the interface theme name; see the class note on why this is a `String`
  * @param diffViewStyle
  *   how diffs are rendered — Forgejo's own vocabulary, verbatim
  * @param hidesEmail
  *   whether the account's email is hidden from other users
  * @param hidesActivity
  *   whether the public activity feed is hidden
  * @param hidesPronouns
  *   whether [[pronouns]] is hidden from other users. Note that the value is still returned here, to the account's own
  *   credentials — this flag is about what others see
  * @param showsRepoUnitHints
  *   whether the repository view offers hints for units that are enabled but empty
  */
final case class UserSettings(
    fullName: Option[String],
    website: Option[String],
    location: Option[String],
    description: Option[String],
    pronouns: Option[String],
    language: Option[String],
    theme: Option[String],
    diffViewStyle: Option[String],
    hidesEmail: Boolean,
    hidesActivity: Boolean,
    hidesPronouns: Boolean,
    showsRepoUnitHints: Boolean,
)
