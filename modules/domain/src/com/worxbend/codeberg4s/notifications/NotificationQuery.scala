package com.worxbend.codeberg4s.notifications

import java.time.Instant

/** The filters both notification listings accept, as one value rather than as five parameters.
  *
  * `GET /notifications` and `GET /repos/{owner}/{repo}/notifications` declare exactly the same filter set, so one type
  * serves both. Built by starting from [[NotificationQuery.Empty]] and naming what should change:
  *
  * {{{
  * NotificationQuery.Empty
  *   .includingRead
  *   .withSubjects(Vector(NotificationSubjectFilter.Pull))
  *   .updatedSince(lastSync)
  * }}}
  *
  * '''Only what is set is sent.''' An unset filter contributes no query parameter at all, which matters because
  * Forgejo's defaults are not this library's to guess: the documented default is unread and pinned threads, and an
  * empty `status-types=` is not the same request as no `status-types` at all.
  *
  * The builder methods exist because `.scalafix.conf` bans default arguments, and a five-argument `copy` at every call
  * site would be worse than either. Each returns a new query; the type is immutable and safe to share.
  *
  * ==`all` and `status-types` overlap, and nobody has measured how==
  *
  * `all=true` is documented as "show notifications marked as read", which is also what `status-types=read` asks for.
  * Which one wins when both are sent, and whether `all=true` widens or replaces `status-types`, is not stated by the
  * spec and could not be probed — the endpoint needs a token, so `golden/MANIFEST.md` has no capture of it. This
  * library sends whatever the caller set and does not reconcile the two; if that matters to a caller, set one of them
  * and not both.
  *
  * @param includeRead
  *   whether to send `all=true`. `false` sends nothing, because `all=false` is Forgejo's own default and sending it
  *   would assert a behaviour nobody verified; use [[includingRead]] rather than writing the flag at a call site
  * @param statuses
  *   which `status-types` to ask for; empty omits the parameter entirely
  * @param subjects
  *   which `subject-type` values to ask for; empty omits the parameter entirely. Note that no value here can select
  *   commit notifications — see [[NotificationSubjectFilter]]
  * @param since
  *   only threads updated at or after this instant, Forgejo's `since`
  * @param before
  *   only threads updated at or before this instant, Forgejo's `before`
  */
final case class NotificationQuery(
    includeRead: Boolean,
    statuses: Vector[NotificationStatus],
    subjects: Vector[NotificationSubjectFilter],
    since: Option[Instant],
    before: Option[Instant],
):

  /** Asks for read threads as well, by sending `all=true`. */
  def includingRead: NotificationQuery = copy(includeRead = true)

  /** Stops sending `all`, leaving the instance's own default in force. */
  def unreadOnly: NotificationQuery = copy(includeRead = false)

  /** Restricts to `values`; an empty vector removes the filter. */
  def withStatuses(values: Vector[NotificationStatus]): NotificationQuery = copy(statuses = values)

  /** Restricts to `values`; an empty vector removes the filter. */
  def withSubjects(values: Vector[NotificationSubjectFilter]): NotificationQuery = copy(subjects = values)

  /** Restricts to threads updated at or after `moment`. */
  def updatedSince(moment: Instant): NotificationQuery = copy(since = Some(moment))

  /** Restricts to threads updated at or before `moment`. */
  def updatedBefore(moment: Instant): NotificationQuery = copy(before = Some(moment))

object NotificationQuery:

  /** No filters at all — whatever the instance serves by default, documented as unread and pinned threads.
    *
    * The starting point for every query: there is no zero-argument constructor, because a query with defaults would
    * hide which of them are this library's and which are the instance's.
    */
  val Empty: NotificationQuery =
    NotificationQuery(
      includeRead = false,
      statuses    = Vector.empty,
      subjects    = Vector.empty,
      since       = None,
      before      = None,
    )
