package com.worxbend.codeberg4s.repositories.admin

import java.time.Instant

/** Whether the authenticated account watches a repository — Forgejo's `WatchInfo`.
  *
  * Watching is what puts a repository's issues and pull requests into the account's notification stream. It is
  * unrelated to starring, which is a bookmark and has its own endpoints.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' The endpoint describes the '''calling'''
  * account, so an anonymous harvest could not capture it.
  *
  * ==Absence is a `404`, not a `false`==
  *
  * Forgejo answers `404` when the account does not watch the repository — the spec says so in as many words: "User is
  * not watching this repo or repo do not exist". That means [[com.worxbend.codeberg4s.repositories.admin.WatchStatus]]
  * is what a watcher gets, and a non-watcher gets a failure with the same status a missing repository produces. The two
  * are indistinguishable from the response alone, which is why `RepositoryAdminApi.subscription` documents it rather
  * than pretending the endpoint answers a Boolean.
  *
  * @param subscribed
  *   whether the account watches the repository
  * @param ignored
  *   whether the account has muted it, which suppresses notifications even while subscribed
  * @param reason
  *   why the subscription exists, when Forgejo gives one. Untyped on the wire — the spec declares the property with no
  *   type at all — so it is carried as text and is `None` for the `null` Forgejo actually sends
  * @param url
  *   the API URL of the subscription
  * @param repositoryUrl
  *   the API URL of the repository being watched
  * @param createdAt
  *   when the subscription started
  */
final case class WatchStatus(
    subscribed: Boolean,
    ignored: Boolean,
    reason: Option[String],
    url: Option[String],
    repositoryUrl: Option[String],
    createdAt: Option[Instant],
):

  /** Whether notifications actually reach the account: subscribed and not muted. */
  def isNotifying: Boolean = subscribed && !ignored

/** How far a fork has drifted from what it was forked from — Forgejo's `SyncForkInfo`.
  *
  * Answered by the two `GET /repos/{owner}/{repo}/sync_fork` endpoints, and the thing to read before asking Forgejo to
  * actually perform the sync.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.'''
  *
  * @param allowed
  *   whether Forgejo will perform the sync. `false` covers every reason at once — the branch has diverged rather than
  *   merely fallen behind, the repository is not a fork, the branch does not exist upstream, or the caller lacks write
  *   access — and the response carries nothing that tells them apart
  * @param commitsBehind
  *   how many commits upstream has that this fork does not. `0` with [[allowed]] true means there is nothing to do
  * @param baseCommit
  *   the commit the upstream branch is at, as a raw id string. Left unvalidated rather than parsed into a
  *   [[com.worxbend.codeberg4s.repositories.CommitSha]], because a fork with no upstream branch reports `""` here and a
  *   smart constructor would turn that into a decoding failure for a response that is perfectly well-formed
  * @param forkCommit
  *   the commit this fork's branch is at, on the same terms as [[baseCommit]]
  */
final case class ForkSyncInfo(
    allowed: Boolean,
    commitsBehind: Long,
    baseCommit: Option[String],
    forkCommit: Option[String],
):

  /** Whether syncing would change anything: permitted, and behind by at least one commit. */
  def isBehind: Boolean = allowed && commitsBehind > 0L

/** Whether a repository will accept another pinned issue or pull request — Forgejo's `NewIssuePinsAllowed`.
  *
  * Forgejo caps how many issues and how many pull requests a repository may pin, and the cap is an instance setting
  * rather than a repository one. This endpoint is how a caller finds out before attempting a pin that would be
  * rejected.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.'''
  *
  * @param issues
  *   whether one more issue may be pinned
  * @param pullRequests
  *   whether one more pull request may be pinned
  */
final case class IssuePinsAllowed(issues: Boolean, pullRequests: Boolean)
