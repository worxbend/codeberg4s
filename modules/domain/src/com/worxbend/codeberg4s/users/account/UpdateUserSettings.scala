package com.worxbend.codeberg4s.users.account

/** What `PATCH /user/settings` is told.
  *
  * {{{
  * UpdateUserSettings.Empty.describedAs("maintainer of nothing in particular").hidingEmail
  * }}}
  *
  * ==Every field is optional, and absent means "leave it alone"==
  *
  * `UserSettingsOptions` declares no `required` list and Forgejo applies only the keys it receives, which is modelled
  * literally: a field left `None` is not rendered at all, so [[UpdateUserSettings.Empty]] is a request that changes
  * nothing. Clearing a text field is spelled by setting it to the empty string — `Some("")` — which is a different
  * request from `None` and the two must not collapse.
  *
  * ==This is an assignment of whatever it names==
  *
  * Every key this command sends states a value to store; a key it does not send leaves what is stored alone. Setting a
  * value twice is setting it once, which is what makes the call idempotent and therefore safe to retry — see
  * [[com.worxbend.codeberg4s.users.account.UserAccountApi.updateSettings]], which states the argument.
  *
  * @param fullName
  *   the display name to store
  * @param website
  *   the profile link to store
  * @param location
  *   the location to store
  * @param description
  *   the biography to store
  * @param pronouns
  *   the pronouns to store
  * @param language
  *   the interface language tag to store. Forgejo rejects a tag it does not ship, which arrives as a `422`
  * @param theme
  *   the interface theme name to store, on the same terms as [[language]]
  * @param diffViewStyle
  *   the diff rendering style to store
  * @param hidesEmail
  *   whether to hide the account's email from other users
  * @param hidesActivity
  *   whether to hide the public activity feed
  * @param hidesPronouns
  *   whether to hide the pronouns from other users
  * @param showsRepoUnitHints
  *   whether the repository view should offer unit hints
  */
final case class UpdateUserSettings(
    fullName: Option[String],
    website: Option[String],
    location: Option[String],
    description: Option[String],
    pronouns: Option[String],
    language: Option[String],
    theme: Option[String],
    diffViewStyle: Option[String],
    hidesEmail: Option[Boolean],
    hidesActivity: Option[Boolean],
    hidesPronouns: Option[Boolean],
    showsRepoUnitHints: Option[Boolean],
):

  /** Stores `value` as the display name; the empty string clears it. */
  def named(value: String): UpdateUserSettings = copy(fullName = Some(value))

  /** Stores `value` as the profile link; the empty string clears it. */
  def linkingTo(value: String): UpdateUserSettings = copy(website = Some(value))

  /** Stores `value` as the location; the empty string clears it. */
  def locatedIn(value: String): UpdateUserSettings = copy(location = Some(value))

  /** Stores `value` as the biography; the empty string clears it. */
  def describedAs(value: String): UpdateUserSettings = copy(description = Some(value))

  /** Stores `value` as the pronouns; the empty string clears them. */
  def withPronouns(value: String): UpdateUserSettings = copy(pronouns = Some(value))

  /** Stores `tag` as the interface language, for example `en-US`. */
  def inLanguage(tag: String): UpdateUserSettings = copy(language = Some(tag))

  /** Stores `name` as the interface theme. */
  def themed(name: String): UpdateUserSettings = copy(theme = Some(name))

  /** Stores `style` as the diff rendering style. */
  def viewingDiffsAs(style: String): UpdateUserSettings = copy(diffViewStyle = Some(style))

  /** Hides the account's email from other users. */
  def hidingEmail: UpdateUserSettings = copy(hidesEmail = Some(true))

  /** Shows the account's email to other users. */
  def showingEmail: UpdateUserSettings = copy(hidesEmail = Some(false))

  /** Hides the public activity feed. */
  def hidingActivity: UpdateUserSettings = copy(hidesActivity = Some(true))

  /** Shows the public activity feed. */
  def showingActivity: UpdateUserSettings = copy(hidesActivity = Some(false))

  /** Hides the pronouns from other users. */
  def hidingPronouns: UpdateUserSettings = copy(hidesPronouns = Some(true))

  /** Shows the pronouns to other users. */
  def showingPronouns: UpdateUserSettings = copy(hidesPronouns = Some(false))

  /** Asks the repository view to offer unit hints. */
  def showingRepoUnitHints: UpdateUserSettings = copy(showsRepoUnitHints = Some(true))

  /** Asks the repository view to suppress unit hints. */
  def hidingRepoUnitHints: UpdateUserSettings = copy(showsRepoUnitHints = Some(false))

object UpdateUserSettings:

  /** The command that changes nothing, and the starting point for every other one. */
  val Empty: UpdateUserSettings =
    UpdateUserSettings(
      fullName           = None,
      website            = None,
      location           = None,
      description        = None,
      pronouns           = None,
      language           = None,
      theme              = None,
      diffViewStyle      = None,
      hidesEmail         = None,
      hidesActivity      = None,
      hidesPronouns      = None,
      showsRepoUnitHints = None,
    )
