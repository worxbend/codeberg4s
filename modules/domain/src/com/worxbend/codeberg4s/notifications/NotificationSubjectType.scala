package com.worxbend.codeberg4s.notifications

import java.util.Locale

/** What a notification is about: the `subject.type` discriminator of [[NotificationSubject]].
  *
  * Forgejo's `NotificationSubject` is a union in spirit and a flat object on the wire — one shape whose meaning depends
  * entirely on this field. The spec is no help in enumerating it: `NotifySubjectType` is declared as a bare
  * `{"type": "string"}` with the description "NotifySubjectType represent type of notification subject" and '''no'''
  * enum list. The four cases below come from the description of `NotificationSubject` itself ("contains the
  * notification subject (Issue/Pull/Commit)") plus the `subject-type` query filter, which does enumerate — and
  * enumerates a '''different''' set. See [[NotificationSubjectFilter]] for that mismatch.
  *
  * ==Why there is an `Other` case==
  *
  * [[com.worxbend.codeberg4s.issues.LifecycleState]] deliberately has no "unknown" case, because an issue is open or
  * closed and a third answer means the model is wrong. This enum is the opposite situation and gets the opposite
  * treatment: the set is not enumerated anywhere machine-readable, no live capture exists to check it against, and
  * Forgejo is free to add a subject type in any release. Failing a whole page of notifications over one unrecognised
  * discriminator would be a worse answer than handing the caller the raw string, so [[Other]] carries it verbatim.
  *
  * ==Evidence==
  *
  * Spec-derived and '''unverified'''. `golden/notification/list-synthetic.json` is hand-authored, and the only two
  * values it contains — `"Issue"` and `"Pull"` — were written by the same hand that wrote this enum. Treat the exact
  * capitalisation as the least certain thing here; [[from]] is case-insensitive for that reason.
  */
enum NotificationSubjectType:

  /** An issue thread. */
  case Issue

  /** A pull-request thread. */
  case Pull

  /** A commit thread — no `subject-type` filter can ask for these; see [[NotificationSubjectFilter]]. */
  case Commit

  /** A repository-level thread, such as a transfer request. */
  case Repository

  /** A discriminator this library does not recognise, kept exactly as the instance spelled it.
    *
    * @param raw
    *   the trimmed wire value
    */
  case Other(raw: String)

  /** The value as it appears in the `subject.type` field, round-tripping [[Other]] unchanged. */
  def wireValue: String =
    this match
      case Issue      => "Issue"
      case Pull       => "Pull"
      case Commit     => "Commit"
      case Repository => "Repository"
      case Other(raw) => raw

object NotificationSubjectType:

  private val ByLowercaseName: Map[String, NotificationSubjectType] =
    Map(
      "issue"      -> Issue,
      "pull"       -> Pull,
      "commit"     -> Commit,
      "repository" -> Repository,
    )

  /** Reads a `subject.type` value.
    *
    * '''Total.''' Anything unrecognised becomes [[Other]] with the trimmed input, so this never fails and never throws.
    * Matching ignores case: the observed spelling is capitalised (`"Issue"`), the query filter's spelling is not
    * (`"issue"`), and neither is verified against a live instance.
    */
  def from(value: String): NotificationSubjectType =
    val trimmed = value.trim

    ByLowercaseName.getOrElse(trimmed.toLowerCase(Locale.ROOT), Other(trimmed))
