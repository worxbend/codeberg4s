package com.worxbend.codeberg4s.users.social

import java.util.Locale

/** What an access-token scope is a scope '''over''' — the part after the colon in `read:repository`.
  *
  * '''Derived from `spec/swagger.v1.json`.''' `CreateAccessTokenOption.scopes` declares no `enum`; it carries only an
  * `example` array, and these eight categories are exactly the ones named in it. That is evidence of eight categories
  * and of nothing else, which is why [[TokenScope.Other]] exists and why nothing here claims the list is complete.
  */
enum TokenCategory:

  /** Federation endpoints — `/user/activitypub/…` and an account's outbox. */
  case ActivityPub

  /** Issues, their comments, labels, milestones and tracked time. */
  case Issue

  /** Everything Forgejo files under miscellaneous: markup rendering, templates, instance settings. */
  case Misc

  /** Notification threads and their read state. */
  case Notification

  /** Organisations, their teams and their membership. */
  case Organization

  /** The package registry. */
  case Package

  /** Repositories and everything hanging off one. */
  case Repository

  /** The account itself — its profile, emails, keys and tokens. */
  case User

  /** The `snake_case` spelling Forgejo uses after the colon. */
  def wireValue: String =
    this match
      case ActivityPub  => "activitypub"
      case Issue        => "issue"
      case Misc         => "misc"
      case Notification => "notification"
      case Organization => "organization"
      case Package      => "package"
      case Repository   => "repository"
      case User         => "user"

object TokenCategory:

  /** Parses the part after the colon, or `None` for a category this release does not know.
    *
    * Matching trims and is case-insensitive, because nothing but the spec's example guarantees the casing.
    */
  def parse(value: String): Option[TokenCategory] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "activitypub"  => Some(ActivityPub)
      case "issue"        => Some(Issue)
      case "misc"         => Some(Misc)
      case "notification" => Some(Notification)
      case "organization" => Some(Organization)
      case "package"      => Some(Package)
      case "repository"   => Some(Repository)
      case "user"         => Some(User)
      case _              => None

/** One permission an access token carries.
  *
  * ==Why this is not a `String`==
  *
  * Forgejo does not reject a scope it does not recognise: it stores the token with the scopes it understood and
  * silently ignores the rest. A token created with `"read:repositories"` — plural, and wrong — is created successfully
  * and can do nothing, and the first sign of the mistake is a `403` from an unrelated call days later. Making the
  * vocabulary a type moves that failure to the line that wrote the typo.
  *
  * [[Other]] is the escape hatch, and it is deliberately awkward to reach: [[TokenScope.parse]] produces it when
  * '''reading''' a token whose scope this release does not know, so a listing never fails on a Forgejo release that
  * added one. A caller who genuinely needs to send an unmodelled scope can construct it, and by naming `Other` they say
  * out loud that nothing checked the spelling.
  *
  * ==`all` is not the union of the others==
  *
  * [[All]] is its own scope string and grants everything the account can do. It is not equivalent to sending every
  * [[Read]] and [[Write]] this type can spell, because the set this type can spell is only what the spec's example
  * named.
  */
enum TokenScope:

  /** Everything the account can do. See the type note — this is one scope string, not a union. */
  case All

  /** Read access to one category. */
  case Read(category: TokenCategory)

  /** Read and write access to one category. Forgejo's `write:` prefix implies the matching `read:`. */
  case Write(category: TokenCategory)

  /** A scope string this release does not model, carried verbatim.
    *
    * Produced by [[TokenScope.parse]] for anything unrecognised, so reading a token never fails on a vocabulary this
    * library has not caught up with. Constructing one deliberately is permitted and unchecked — see the type note.
    */
  case Other(raw: String)

  /** The exact string Forgejo expects in `scopes`. */
  def wireValue: String =
    this match
      case All             => TokenScope.AllValue
      case Read(category)  => s"read:${category.wireValue}"
      case Write(category) => s"write:${category.wireValue}"
      case Other(raw)      => raw

object TokenScope:

  /** The scope string that grants everything. */
  val AllValue: String = "all"

  /** The prefix of a read-only scope. */
  val ReadPrefix: String = "read:"

  /** The prefix of a read/write scope. */
  val WritePrefix: String = "write:"

  /** Parses a scope string, never failing.
    *
    * Anything that is not `all`, `read:<known>` or `write:<known>` becomes [[Other]] carrying the '''trimmed
    * original''' — including a known prefix over an unknown category, such as `read:admin`. A caller who wants to know
    * whether a token's scopes were all understood matches on the result; a caller who only wants to send them back gets
    * a value that round-trips through [[TokenScope.wireValue]] unchanged.
    *
    * Matching is case-insensitive on the prefix and on the category, because nothing but the spec's example guarantees
    * the casing.
    */
  def parse(value: String): TokenScope =
    val trimmed = value.trim
    val lower   = trimmed.toLowerCase(Locale.ROOT)

    if lower.equals(AllValue) then All
    else if lower.startsWith(ReadPrefix) then
      TokenCategory.parse(lower.drop(ReadPrefix.length)).map(Read.apply).getOrElse(Other(trimmed))
    else if lower.startsWith(WritePrefix) then
      TokenCategory.parse(lower.drop(WritePrefix.length)).map(Write.apply).getOrElse(Other(trimmed))
    else Other(trimmed)
