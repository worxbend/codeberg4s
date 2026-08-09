package com.worxbend.codeberg4s.issues

import java.time.Instant

/** Whether the authenticated account is following one issue — what
  * `GET /repos/{owner}/{repo}/issues/{index}/subscriptions/check` answers.
  *
  * '''Derived from `spec/swagger.v1.json`''' — Forgejo's `WatchInfo`, the same model the repository-watch endpoints
  * return. It is decoded into a type of this group's own rather than shared, because the two are owned by different
  * groups per `docs/LEDGER.md` and because the URLs on it point at an issue here and at a repository there.
  *
  * '''`subscribed` and `ignored` are not opposites.''' An account that has explicitly muted an issue is `ignored` and
  * not `subscribed`; one that has never interacted is neither. Both are booleans on the wire and both are kept, rather
  * than folded into one three-valued type, because the wire does not promise they are exclusive and a model that
  * assumed it would have to decide what to do when they are not.
  *
  * One wire field is dropped: `reason` is declared in the spec with '''no type at all''' — the property has an
  * `x-go-name` and nothing else — so there is nothing to decode it as. The DTO ignores the key rather than guessing.
  *
  * @param isSubscribed
  *   whether the account receives notifications for this issue
  * @param isIgnored
  *   whether the account has explicitly muted it
  * @param url
  *   the API URL of the subscription itself
  * @param repositoryUrl
  *   the API URL of the repository the issue is in
  * @param createdAt
  *   when the subscription was recorded
  */
final case class IssueSubscription private[codeberg4s] (
    isSubscribed: Boolean,
    isIgnored: Boolean,
    url: Option[String],
    repositoryUrl: Option[String],
    createdAt: Option[Instant],
)
