package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import scala.concurrent.duration.FiniteDuration

import java.time.Instant

/** What `POST /repos/{owner}/{repo}/issues/{index}/times` is told.
  *
  * '''Derived from `spec/swagger.v1.json`''' — Forgejo's `AddTimeOption`, whose one required property is `time`, "time
  * in seconds". No golden capture of this request exists.
  *
  * ==Seconds are the wire's unit, not this type's==
  *
  * [[spent]] is a `FiniteDuration` so that a call site reads `2.hours` rather than `7200`, and the conversion happens
  * once at the wire boundary. That makes one thing the caller's problem, and it is checked here rather than silently
  * truncated: '''a duration finer than a second cannot be sent'''. Forgejo has no sub-second field, so `90.seconds` is
  * accepted and `1500.millis` is rejected by [[AddTrackedTime.of]] instead of quietly becoming one second.
  *
  * @param spent
  *   how long was worked; positive and a whole number of seconds, see [[AddTrackedTime.of]]
  * @param userName
  *   the login to attribute the time to. Requires the token to be allowed to log time for somebody else; absent means
  *   the authenticated account
  * @param createdAt
  *   when the entry should be recorded as having been made, for an import that is replaying history
  */
final case class AddTrackedTime(spent: FiniteDuration, userName: Option[String], createdAt: Option[Instant]):

  /** Attributes the time to `login` rather than to the authenticated account. */
  def attributedTo(login: String): AddTrackedTime = copy(userName = Some(login))

  /** Backdates the entry, which is what an importer wants and nothing else does. */
  def recordedAt(moment: Instant): AddTrackedTime = copy(createdAt = Some(moment))

object AddTrackedTime:

  /** How many nanoseconds a second holds, used to detect a duration the wire cannot carry. */
  private val NanosPerSecond: Long = 1000000000L

  /** Builds the command from a duration.
    *
    * Rejects a duration that is zero or negative, and one that is not a whole number of seconds — see the type note for
    * why truncating instead would be worse.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"spent"` field
    */
  def of(spent: FiniteDuration): Either[ValidationError, AddTrackedTime] =
    val nanos = spent.toNanos
    if nanos <= 0L then Left(ValidationError("spent", "must be a positive duration"))
    else if nanos % NanosPerSecond > 0L then
      Left(ValidationError("spent", "must be a whole number of seconds, which is all Forgejo records"))
    else Right(AddTrackedTime(spent = spent, userName = None, createdAt = None))
