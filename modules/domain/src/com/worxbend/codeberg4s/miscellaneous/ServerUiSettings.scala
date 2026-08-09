package com.worxbend.codeberg4s.miscellaneous

/** What `GET /settings/ui` says about the instance's web interface.
  *
  * The fourth and last of the `/settings` family, beside [[ServerApiSettings]], [[ServerRepositorySettings]] and
  * [[ServerAttachmentSettings]]. It is the one with no effect on what the API will accept: nothing here changes whether
  * a request succeeds. What it is for is agreeing with the web UI — a client that offers a reaction the instance does
  * not allow gets a `422` from the reaction endpoints, and reading [[allowedReactions]] first is how that is avoided.
  *
  * '''Derived from `spec/swagger.v1.json`'s `GeneralUISettings` definition, not from a captured response.''' The three
  * other settings endpoints have golden fixtures; this one was not harvested.
  *
  * @param allowedReactions
  *   the emoji the instance accepts on [[com.worxbend.codeberg4s.issues.Reaction]] endpoints, as bare names such as
  *   `+1` and `heart`. Empty when the instance reported none, which is not the same as "reactions are off" — it means
  *   the instance did not say
  * @param customEmojis
  *   the names of emoji this deployment defines beyond the standard set, which a renderer has to resolve against the
  *   instance rather than against a Unicode table
  * @param defaultTheme
  *   the theme a signed-out visitor sees, absent when the instance did not report one
  */
final case class ServerUiSettings private[codeberg4s] (
    allowedReactions: Vector[String],
    customEmojis: Vector[String],
    defaultTheme: Option[String],
):

  /** Whether `reaction` is one the instance accepts.
    *
    * Answers `false` when [[allowedReactions]] is empty, because an empty list is "the instance did not say" and this
    * library does not turn silence into permission.
    */
  def allows(reaction: String): Boolean =
    allowedReactions.contains(reaction)
