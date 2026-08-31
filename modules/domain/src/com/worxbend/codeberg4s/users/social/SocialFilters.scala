package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.ValidationError

import java.time.{Instant, LocalDate}

/** The remote account `POST /user/activitypub/follow` is asked to follow.
  *
  * '''Derived from `spec/swagger.v1.json`'s `APRemoteFollowOption`''', whose single property is `target` and which
  * declares nothing required. The endpoint is federation, not the local follow graph: the value names an ActivityPub
  * actor on another instance, typically as its actor URI, and following it is a different operation from
  * `UserSocialApi.follow`, which takes a [[com.worxbend.codeberg4s.users.Username]] on this instance.
  *
  * '''The spelling is the instance's business.''' Forgejo has accepted both an actor URI and a `@user@host` handle
  * across releases, and the spec constrains neither, so this type checks only that the value can travel and leaves the
  * rest to the `404` the endpoint answers for a target it cannot resolve.
  */
opaque type RemoteFollowTarget = String

object RemoteFollowTarget:

  /** The field name a rejected value is reported under. */
  private val Field: String = "remoteFollowTarget"

  /** Parses a remote follow target.
    *
    * Trims surrounding whitespace, because the value is routinely pasted out of a browser. Rejects an empty or blank
    * value and any value containing a control character.
    *
    * @return
    *   the trimmed target, or a [[ValidationError]] on the `"remoteFollowTarget"` field
    */
  def from(value: String): Either[ValidationError, RemoteFollowTarget] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError(Field, "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(Field, "must not contain a control character"))
    else Right(trimmed)

  extension (target: RemoteFollowTarget)

    /** The target as a string, ready to be rendered into a request body. */
    def value: String = target

/** The filters `GET /users/{username}/activities/feeds` accepts.
  *
  * '''Derived from `spec/swagger.v1.json`''': the operation declares `only-performed-by` and `date` beside the paging
  * parameters. No golden capture exists.
  *
  * '''Only what is set is sent.''' An absent filter is not the same request as a false one — omitting
  * `only-performed-by` asks for the account's whole feed including actions others took on its repositories, and
  * `only-performed-by=false` says the same thing in a way that pins the behaviour against a future default change. Both
  * spellings are reachable, and which one goes out is the caller's choice rather than this library's.
  *
  * @param onlyPerformedBy
  *   restrict the feed to actions the account itself performed
  * @param date
  *   restrict the feed to one calendar day, in the instance's own time zone. The wire format is `date`, which is
  *   `yyyy-MM-dd`, so a [[java.time.LocalDate]] is exactly the right amount of information — an [[java.time.Instant]]
  *   here would carry a time of day the endpoint discards
  */
final case class ActivityFeedQuery private[codeberg4s] (onlyPerformedBy: Option[Boolean], date: Option[LocalDate]):

  /** Restricts the feed to actions the account performed itself. */
  def performedByTheAccount: ActivityFeedQuery = copy(onlyPerformedBy = Some(true))

  /** States explicitly that actions by others are wanted too. See the class note on why that is not the same as
    * omitting the filter.
    */
  def performedByAnyone: ActivityFeedQuery = copy(onlyPerformedBy = Some(false))

  /** Restricts the feed to one calendar day. */
  def on(day: LocalDate): ActivityFeedQuery = copy(date = Some(day))

object ActivityFeedQuery:

  /** No filters at all — the whole feed the credentials may see. */
  val Empty: ActivityFeedQuery = ActivityFeedQuery(onlyPerformedBy = None, date = None)

/** The time window `GET /user/times` accepts.
  *
  * '''Derived from `spec/swagger.v1.json`''': the operation declares `since` and `before` beside the paging parameters,
  * both `date-time`. No golden capture exists.
  *
  * Deliberately '''not''' [[com.worxbend.codeberg4s.issues.TrackedTimeQuery]], which this endpoint would only ever use
  * two thirds of: that type also carries a `user` filter, which the repository-scoped listing accepts and this one does
  * not. Offering a field that is silently dropped is worse than a second small type.
  *
  * '''Only what is set is sent.''' An open-ended window asks for everything the credentials may see, which on a busy
  * account is a lot of pages.
  *
  * @param since
  *   only entries updated at or after this instant
  * @param before
  *   only entries updated at or before this instant
  */
final case class TrackedTimeWindow private[codeberg4s] (since: Option[Instant], before: Option[Instant]):

  /** Restricts the listing to entries updated at or after `moment`. */
  def updatedSince(moment: Instant): TrackedTimeWindow = copy(since = Some(moment))

  /** Restricts the listing to entries updated at or before `moment`. */
  def updatedBefore(moment: Instant): TrackedTimeWindow = copy(before = Some(moment))

object TrackedTimeWindow:

  /** No window at all — every entry the credentials may see. */
  val Empty: TrackedTimeWindow = TrackedTimeWindow(since = None, before = None)
