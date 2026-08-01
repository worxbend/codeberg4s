package com.worxbend.codeberg4s.notifications

/** A value of the `status-types` filter on the notification listings.
  *
  * Three named cases rather than three loose strings, and rather than a pair of Booleans: `status-types` is a repeated
  * query parameter, so "unread and pinned" is two values and not a flag each. The spec spells the vocabulary out in
  * prose — "Options are: unread, read and/or pinned" — while typing the parameter as an unconstrained array of string,
  * which is why the vocabulary lives here instead of being trusted to callers.
  *
  * '''Absent is not the same as all three.''' Forgejo's documented default for the listing is unread and pinned, so a
  * caller who wants read threads too has to say so — either through [[NotificationQuery.withStatuses]] or through
  * [[NotificationQuery.includingRead]], which sets the separate `all` parameter. See [[NotificationQuery]] for how the
  * two interact, and for the honest admission that nobody has checked.
  */
enum NotificationStatus:

  /** Threads the user has not read. */
  case Unread

  /** Threads the user has already read. */
  case Read

  /** Threads the user pinned. */
  case Pinned

  /** The value to put in a `status-types` query parameter. */
  def wireValue: String =
    this match
      case Unread => "unread"
      case Read   => "read"
      case Pinned => "pinned"
