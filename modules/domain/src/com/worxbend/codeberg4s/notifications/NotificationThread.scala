package com.worxbend.codeberg4s.notifications

import com.worxbend.codeberg4s.repositories.Repository

import java.time.Instant

/** One entry in a user's notification inbox.
  *
  * Owned by this group per `docs/LEDGER.md`. The embedded repository is the model
  * `com.worxbend.codeberg4s.repositories` already owns, not a reduced copy — the ledger's rule is that the wave which
  * needs a shared model first owns it, and forking one is a review-blocking defect.
  *
  * ==Read this before trusting the shape==
  *
  * '''Spec-derived and unverified against a live instance.''' Every other model in this library was checked against a
  * captured response body; this one could not be. `GET /notifications` answers `401 token is required` without
  * credentials, so the anonymous harvest that produced `golden/` never reached it, and the single fixture that exists —
  * `golden/notification/list-synthetic.json` — is hand-authored from `definitions.NotificationThread` and
  * `definitions.NotificationSubject` in `spec/swagger.v1.json`. `golden/MANIFEST.md` marks it `synthetic` and says the
  * same thing: shape-only evidence, never evidence of optionality. Only its embedded `repository` object is real, being
  * a copy of `repository/repo-single-community.json`.
  *
  * What follows from that:
  *
  *   - the field set is the spec's, and the spec is known to be wrong about optionality everywhere else
  *     (`docs/HAZARDS.md` §1: zero of 246 response definitions declare anything `required`, and live payloads send
  *     `null` for fields declared as arrays);
  *   - so everything except [[id]] is optional here, and a value the instance omits is simply absent rather than a
  *     failure;
  *   - [[id]] is required because a thread that cannot address itself cannot be read or marked read, which is what the
  *     other five operations in this group do.
  *
  * If a live capture ever contradicts this model, the capture wins. That is the standing rule for every disagreement
  * between `golden/` and the spec, and it applies with more force here than anywhere else in the library.
  *
  * @param id
  *   the thread's identifier, the only argument `/notifications/threads/{id}` takes
  * @param subject
  *   what the notification is about; see [[NotificationSubject]]
  * @param repository
  *   the repository the thread belongs to. Forgejo embeds a full `Repository` here rather than a reduced one, so far as
  *   the spec says
  * @param isUnread
  *   whether the thread is still unread; `false` when the instance did not say
  * @param isPinned
  *   whether the user pinned the thread; `false` when the instance did not say
  * @param url
  *   the API URL of this thread, verbatim, for the same reason [[NotificationSubject.url]] stays a string
  * @param updatedAt
  *   when the thread last changed. Absent when the instance omitted it or sent one of Forgejo's zero-time sentinels;
  *   see [[com.worxbend.codeberg4s.codec.Timestamps]]
  */
final case class NotificationThread private[codeberg4s] (
    id: NotificationThreadId,
    subject: Option[NotificationSubject],
    repository: Option[Repository],
    isUnread: Boolean,
    isPinned: Boolean,
    url: Option[String],
    updatedAt: Option[Instant],
)
