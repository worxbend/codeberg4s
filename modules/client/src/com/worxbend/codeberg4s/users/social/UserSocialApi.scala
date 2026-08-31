package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.issues.TrackedTime
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.admin.RepositoryActivity
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.Username
import com.worxbend.codeberg4s.users.social.wire.RemoteFollowOptionDto
import com.worxbend.codeberg4s.users.social.wire.SocialQueries

import scala.concurrent.Future

/** An account's social graph: whom it follows, what it stars and watches, whom it blocks, and what it has been doing.
  *
  * Reached as `client.users.social`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[UserSocialApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Two families of path==
  *
  * `/user/…` means "whoever the configured credentials are" and needs a token; `/users/{username}/…` names an account
  * explicitly and the spec marks it anonymous. The spec is not to be trusted on that second point — `docs/HAZARDS.md`
  * §2 measures that '''no''' operation in the document carries per-endpoint security information at all, and the
  * fixture manifest records codeberg.org answering `401` to an anonymous `/users/{u}/followers`. Configure a token
  * unless a call is known to work without one on the instance being talked to.
  *
  * ==Evidence==
  *
  * '''Every model reached from this class that is new to this group is derived from `spec/swagger.v1.json`, not from a
  * captured response.''' [[BlockedUser]], [[StopWatch]] and [[HeatmapEntry]] have no golden fixture behind them: two of
  * the three endpoints need a token and the third was not among the 61 anonymous captures. [[User]],
  * [[com.worxbend.codeberg4s.repositories.Repository]], [[com.worxbend.codeberg4s.issues.TrackedTime]] and
  * [[com.worxbend.codeberg4s.repositories.admin.RepositoryActivity]] are the existing models and carry their own
  * evidence notes.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when credentials were required and none were
  *     sent or the token was rejected, `403` when the token lacks the scope, and `404` when the account or repository
  *     does not exist '''or''' is not visible to the credentials — Forgejo does not distinguish those two, on purpose.
  *     `422` '''and''' `400` both mean the request was rejected as invalid; `docs/HAZARDS.md` §4 records Forgejo using
  *     `400` where a reader would expect `422`.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field, and an element of a listing is named as `$[n]`.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path is rejected by its own smart constructor before a
  * client is ever involved.
  *
  * ==Pagination==
  *
  * Every listing takes a [[com.worxbend.codeberg4s.paging.PageParams]] and returns one
  * [[com.worxbend.codeberg4s.paging.Page]]; none of them fetches the whole collection. `page` and `limit` are always
  * sent together, because the fixture manifest records list endpoints that ignore `limit` on its own and return
  * everything. Whether more pages exist is decided by the response's `rel="next"` link and never by how many items came
  * back — Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5).
  *
  * [[heatmap]] is the exception: it answers an array and accepts no paging parameters, so it returns a `Vector`.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. The one `POST` uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], because Forgejo offers no idempotency key. Every `PUT` and
  * `DELETE` here uses [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], and that decision is justified per
  * method rather than assumed. The shared part of the argument: each of them sets a '''relationship''' between the
  * calling account and one named subject — followed or not, starred or not, blocked or not — so the end state after N
  * attempts is the state after one, and none of them creates a resource whose identity a repeat could duplicate.
  *
  * ==Three operations that answer a question rather than fetching a thing==
  *
  * [[isFollowing]], [[follows]] and [[isStarred]] are `GET`s whose whole payload is the status code: `204` for yes,
  * `404` for no. They are exposed as `Future[Boolean]` rather than as a `Future[Unit]` that throws on a negative
  * answer, because "I do not follow this account" is not an error. `401` and `403` still arrive as
  * [[com.worxbend.codeberg4s.CodebergError.Api]], so an account the credentials cannot see does not masquerade as an
  * account they do not follow.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class UserSocialApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: UserSocialApi.Attempt = UserSocialApi.Attempt(this)

  // --- following ------------------------------------------------------------

  /** Lists the accounts following the credentials' own account — `GET /user/followers`.
    *
    * The signed-in counterpart of `client.users.followers`, which names an account explicitly. This one needs a token
    * and has no anonymous reading at all.
    *
    * '''Failures.''' The group contract above; a decoding failure means an element carried no `id` or `login`. `GET` is
    * safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def followers(params: PageParams): Future[Page[User]] =
    pipeline.callPage(UserSocialApi.followersRequest(params), params)(using SocialDecoders.users)

  /** Lists the accounts the credentials' own account follows — `GET /user/following`.
    *
    * '''Failures.''' As [[followers]]. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def following(params: PageParams): Future[Page[User]] =
    pipeline.callPage(UserSocialApi.followingRequest(params), params)(using SocialDecoders.users)

  /** Follows an account — `PUT /user/following/{username}`.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]. Following is a relationship,
    * not a resource: the request creates nothing whose identity a repeat could duplicate, and the end state after any
    * number of attempts is "the account is followed". Following an account already followed is a `204`, not a conflict.
    *
    * The one thing a caller should know is what a repeat cannot protect against: account handles are not permanent on
    * Forgejo, so a retry issued after a lost success addresses whoever holds the handle at that moment. That window is
    * bounded by the retry policy — seconds — and a handle changing hands inside it is not a case this library can
    * detect. Callers who need certainty follow by handle once and verify with [[isFollowing]].
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above; `404` means no such account.
    */
  def follow(username: Username): Future[Unit] =
    pipeline.callUnit(UserSocialApi.followRequest(username), RetryEligibility.AlwaysRetry)

  /** Stops following an account — `DELETE /user/following/{username}`.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], by the argument [[follow]]
    * makes in reverse: the end state is "the account is not followed" however many attempts reach the instance. A
    * second attempt after a lost success answers `204` as well — unfollowing an account that is not followed is not an
    * error here, unlike a delete addressed by identifier elsewhere in this library.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above.
    */
  def unfollow(username: Username): Future[Unit] =
    pipeline.callUnit(UserSocialApi.unfollowRequest(username), RetryEligibility.AlwaysRetry)

  /** Whether the credentials' own account follows `username` — `GET /user/following/{username}`.
    *
    * `204` answers yes and `404` answers no; see the class note on why that is a `Boolean` and not a failure. A `404`
    * is not a retryable status, so the `false` answer is never reached by way of an exhausted retry budget.
    *
    * '''Failures.''' The group contract above, minus `404` and minus the decoding case — nothing is parsed. `GET` is
    * safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def isFollowing(username: Username): Future[Boolean] =
    probe(UserSocialApi.isFollowingRequest(username))

  /** Whether `username` follows `target` — `GET /users/{username}/following/{target}`.
    *
    * The third-party form of [[isFollowing]]: neither account has to be the credentials' own. Named `follows` rather
    * than overloading `isFollowing`, so a call site with two handles cannot be misread as one with a handle and an
    * implicit self.
    *
    * '''Failures.''' As [[isFollowing]], with the added subtlety that a `404` here also covers "no such account" —
    * Forgejo answers the same status for an account that does not exist and for one that simply is not followed, and
    * nothing in the response distinguishes them. A caller who needs to tell them apart reads the account with
    * `client.users.get` first. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def follows(username: Username, target: Username): Future[Boolean] =
    probe(UserSocialApi.followsRequest(username, target))

  /** Follows an account on another instance — `POST /user/activitypub/follow`.
    *
    * Federation, not the local follow graph: the target is an ActivityPub actor elsewhere, and the relationship this
    * creates does not appear in [[following]]. See [[RemoteFollowTarget]] for what a target may look like and why this
    * library does not constrain it.
    *
    * '''Never retried''', under [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], because this library repeats
    * no `POST`: Forgejo offers no idempotency key and what the remote instance does with a duplicate follow activity is
    * not this library's to predict.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above; `404` means the target could not be resolved.
    */
  def followRemote(target: RemoteFollowTarget): Future[Unit] =
    pipeline.callUnit(UserSocialApi.followRemoteRequest(target), RetryEligibility.Never)

  // --- stars and watches ----------------------------------------------------

  /** Lists the repositories the credentials' own account has starred — `GET /user/starred`.
    *
    * '''Failures.''' The group contract above; a decoding failure means an element carried no `id`, `name` or `owner`.
    * `GET` is safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def starred(params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(UserSocialApi.starredRequest(params), params)(using SocialDecoders.repositories)

  /** Lists the repositories `username` has starred — `GET /users/{username}/starred`.
    *
    * Only repositories the configured credentials may see are returned, so the same call answers differently for an
    * anonymous client and for the account holder's own token.
    *
    * '''Failures.''' As [[starred]], with `404` for an account that does not exist. `GET` is safe, so the call is
    * retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def starredBy(username: Username, params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(UserSocialApi.starredByRequest(username, params), params)(using SocialDecoders.repositories)

  /** Stars a repository — `PUT /user/starred/{owner}/{repo}`.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], by the same argument [[follow]]
    * makes: a star is a relationship between the account and one repository, nothing is created, and the end state
    * after N attempts is "the repository is starred". Starring an already-starred repository is a `204`.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above; `404` means no such repository, or one the credentials cannot see.
    */
  def star(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(UserSocialApi.starRequest(owner, name), RetryEligibility.AlwaysRetry)

  /** Removes a star — `DELETE /user/starred/{owner}/{repo}`.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], by [[unfollow]]'s argument: the
    * end state is "the repository is not starred", and a second attempt after a lost success answers `204` too.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above.
    */
  def unstar(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(UserSocialApi.unstarRequest(owner, name), RetryEligibility.AlwaysRetry)

  /** Whether the credentials' own account has starred a repository — `GET /user/starred/{owner}/{repo}`.
    *
    * `204` answers yes and `404` answers no; see the class note. As with [[follows]], the `404` also covers a
    * repository that does not exist or that the credentials cannot see, and nothing in the response distinguishes those
    * from "not starred".
    *
    * '''Failures.''' The group contract above, minus `404` and minus the decoding case — nothing is parsed. `GET` is
    * safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def isStarred(owner: Owner, name: RepoName): Future[Boolean] =
    probe(UserSocialApi.isStarredRequest(owner, name))

  /** Lists the repositories the credentials' own account watches — `GET /user/subscriptions`.
    *
    * '''Watching is not starring.''' A star is a bookmark and a public signal; a subscription is what decides whether
    * the account receives notifications from the repository. The two listings overlap only by coincidence.
    *
    * '''Failures.''' As [[starred]]. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def subscriptions(params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(UserSocialApi.subscriptionsRequest(params), params)(using SocialDecoders.repositories)

  /** Lists the repositories `username` watches — `GET /users/{username}/subscriptions`.
    *
    * '''Failures.''' As [[starredBy]]. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def subscriptionsOf(username: Username, params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(UserSocialApi.subscriptionsOfRequest(username, params), params)(using SocialDecoders.repositories)

  // --- blocks ---------------------------------------------------------------

  /** Blocks an account — `PUT /user/block/{username}`.
    *
    * A block stops the other account from following, from opening issues and pull requests, and from commenting.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], by [[follow]]'s argument: the
    * end state is "the account is blocked" however many attempts arrive, and nothing is created whose identity a repeat
    * could duplicate. The `block_id` a listing later shows is the instance's, not something this request names.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above; `422` is what Forgejo answers for a block it will not perform, such as
    * blocking oneself or blocking an organisation.
    */
  def block(username: Username): Future[Unit] =
    pipeline.callUnit(UserSocialApi.blockRequest(username), RetryEligibility.AlwaysRetry)

  /** Removes a block — `PUT /user/unblock/{username}`.
    *
    * A `PUT`, not a `DELETE`: Forgejo models unblocking as its own endpoint rather than as the inverse verb on the
    * block path. Retried for the same reason [[block]] is.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above.
    */
  def unblock(username: Username): Future[Unit] =
    pipeline.callUnit(UserSocialApi.unblockRequest(username), RetryEligibility.AlwaysRetry)

  /** Lists the blocks the credentials' own account has in place — `GET /user/list_blocked`.
    *
    * '''This does not say whom the account has blocked.''' Forgejo's `BlockedUser` model carries a block identifier and
    * a timestamp and no account at all; see [[BlockedUser]] for why that is reproduced rather than papered over.
    *
    * '''Failures.''' The group contract above; a decoding failure means an element carried no `block_id`. `GET` is
    * safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def blocked(params: PageParams): Future[Page[BlockedUser]] =
    pipeline.callPage(UserSocialApi.blockedRequest(params), params)(using SocialDecoders.blockedUsers)

  // --- work ----------------------------------------------------------------

  /** Lists the stopwatches running for the credentials' own account — `GET /user/stopwatches`.
    *
    * '''Failures.''' The group contract above; a decoding failure means an element carried no `issue_index`, which is
    * the only field that says which issue the timer belongs to. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def stopWatches(params: PageParams): Future[Page[StopWatch]] =
    pipeline.callPage(UserSocialApi.stopWatchesRequest(params), params)(using SocialDecoders.stopWatches)

  /** Lists the time the credentials' own account has logged, across every repository — `GET /user/times`.
    *
    * The account-wide counterpart of `client.issues.times`, which is scoped to one issue. The elements are the same
    * [[com.worxbend.codeberg4s.issues.TrackedTime]] values.
    *
    * '''Failures.''' The group contract above; a decoding failure means an element carried no `id` or `time`, and a
    * `422` whose message is a raw Go parse error means the window was rejected — which this library cannot produce,
    * since [[TrackedTimeWindow]] renders instants rather than accepting text.
    *
    * `GET` is safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param window
    *   the `since`/`before` bounds; [[TrackedTimeWindow.Empty]] asks for everything
    */
  def trackedTimes(window: TrackedTimeWindow, params: PageParams): Future[Page[TrackedTime]] =
    pipeline.callPage(UserSocialApi.trackedTimesRequest(window, params), params)(using SocialDecoders.trackedTimes)

  /** Lists what an account has been doing — `GET /users/{username}/activities/feeds`.
    *
    * The feed is what the account's profile page shows: a reverse-chronological list of actions, each an
    * [[com.worxbend.codeberg4s.repositories.admin.ActivityOperation]] and a subject. It is not an audit log, and an
    * entry exists because Forgejo decided the event was worth showing a human.
    *
    * Private entries are filtered by the instance according to what the credentials may see, so the same call answers
    * differently for the account holder and for everyone else.
    *
    * '''Failures.''' The group contract above; a decoding failure means an element carried no `id`. `GET` is safe, so
    * the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param query
    *   the `only-performed-by` and `date` filters; [[ActivityFeedQuery.Empty]] asks for the whole feed
    */
  def activityFeeds(
      username: Username,
      query: ActivityFeedQuery,
      params: PageParams,
  ): Future[Page[RepositoryActivity]] =
    pipeline.callPage(UserSocialApi.activityFeedsRequest(username, query, params), params)(using
      SocialDecoders.activities)

  /** Reads an account's contribution heatmap — `GET /users/{username}/heatmap`.
    *
    * '''Not paged, and deliberately not pretending to be.''' The endpoint declares no `page` or `limit`, so this
    * returns the whole array the instance sent rather than a [[com.worxbend.codeberg4s.paging.Page]] whose `nextPage`
    * would always be empty. The response is bounded by however far back the instance keeps contribution data.
    *
    * Buckets arrive as epoch seconds on the wire and as [[java.time.Instant]] here; see [[HeatmapEntry]] for why that
    * conversion happens at the boundary and what the bucket width does and does not promise.
    *
    * '''Failures.''' The group contract above; a decoding failure means an element carried no `timestamp` or no
    * `contributions`. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def heatmap(username: Username): Future[Vector[HeatmapEntry]] =
    pipeline.call(UserSocialApi.heatmapRequest(username), RetryEligibility.IdempotentOnly)(using SocialDecoders.heatmap)

  /** Sends a status-only `GET` and reads its status as a yes or a no.
    *
    * `204` is yes and `404` is no; every other failure is raised unchanged, so an unreadable subject cannot masquerade
    * as a negative answer. Written once here rather than three times, so the three checks cannot drift.
    */
  private def probe(request: CodebergRequest): Future[Boolean] =
    val sent = pipeline.callUnit(request, RetryEligibility.IdempotentOnly)

    exec.flatMap(exec.attempt(sent)):
      case Right(_)                                            => exec.pure(true)
      case Left(CodebergError.Api(_, UserSocialApi.Absent, _)) => exec.pure(false)
      case Left(error)                                         => exec.raise(error)

/** The requests this group issues and its typed rail. */
object UserSocialApi:

  /** The status Forgejo answers a status-only `GET` with when the answer is no. */
  private val Absent: Int = 404

  /** The stable operation id [[UserSocialApi.followers]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val FollowersOperation: String = "users.social.followers"

  /** The stable operation id of [[UserSocialApi.following]]. */
  val FollowingOperation: String = "users.social.following"

  /** The stable operation id of [[UserSocialApi.follow]]. */
  val FollowOperation: String = "users.social.follow"

  /** The stable operation id of [[UserSocialApi.unfollow]]. */
  val UnfollowOperation: String = "users.social.unfollow"

  /** The stable operation id of [[UserSocialApi.isFollowing]]. */
  val IsFollowingOperation: String = "users.social.isFollowing"

  /** The stable operation id of [[UserSocialApi.follows]]. */
  val FollowsOperation: String = "users.social.follows"

  /** The stable operation id of [[UserSocialApi.followRemote]]. */
  val FollowRemoteOperation: String = "users.social.followRemote"

  /** The stable operation id of [[UserSocialApi.starred]]. */
  val StarredOperation: String = "users.social.starred"

  /** The stable operation id of [[UserSocialApi.starredBy]]. */
  val StarredByOperation: String = "users.social.starredBy"

  /** The stable operation id of [[UserSocialApi.star]]. */
  val StarOperation: String = "users.social.star"

  /** The stable operation id of [[UserSocialApi.unstar]]. */
  val UnstarOperation: String = "users.social.unstar"

  /** The stable operation id of [[UserSocialApi.isStarred]]. */
  val IsStarredOperation: String = "users.social.isStarred"

  /** The stable operation id of [[UserSocialApi.subscriptions]]. */
  val SubscriptionsOperation: String = "users.social.subscriptions"

  /** The stable operation id of [[UserSocialApi.subscriptionsOf]]. */
  val SubscriptionsOfOperation: String = "users.social.subscriptionsOf"

  /** The stable operation id of [[UserSocialApi.block]]. */
  val BlockOperation: String = "users.social.block"

  /** The stable operation id of [[UserSocialApi.unblock]]. */
  val UnblockOperation: String = "users.social.unblock"

  /** The stable operation id of [[UserSocialApi.blocked]]. */
  val BlockedOperation: String = "users.social.blocked"

  /** The stable operation id of [[UserSocialApi.stopWatches]]. */
  val StopWatchesOperation: String = "users.social.stopwatches"

  /** The stable operation id of [[UserSocialApi.trackedTimes]]. */
  val TrackedTimesOperation: String = "users.social.times"

  /** The stable operation id of [[UserSocialApi.activityFeeds]]. */
  val ActivityFeedsOperation: String = "users.social.activityFeeds"

  /** The stable operation id of [[UserSocialApi.heatmap]]. */
  val HeatmapOperation: String = "users.social.heatmap"

  /** The typed rail of [[UserSocialApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.users.social.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: UserSocialApi)(using exec: Exec[Future]):

    /** [[UserSocialApi.followers]] with its failure as a value. The returned `Future` never fails with a
      * [[com.worxbend.codeberg4s.CodebergException]].
      */
    def followers(params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.followers(params))

    /** [[UserSocialApi.following]] with its failure as a value. */
    def following(params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.following(params))

    /** [[UserSocialApi.follow]] with its failure as a value. */
    def follow(username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.follow(username))

    /** [[UserSocialApi.unfollow]] with its failure as a value. */
    def unfollow(username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unfollow(username))

    /** [[UserSocialApi.isFollowing]] with its failure as a value. */
    def isFollowing(username: Username): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.isFollowing(username))

    /** [[UserSocialApi.follows]] with its failure as a value. */
    def follows(username: Username, target: Username): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.follows(username, target))

    /** [[UserSocialApi.followRemote]] with its failure as a value. */
    def followRemote(target: RemoteFollowTarget): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.followRemote(target))

    /** [[UserSocialApi.starred]] with its failure as a value. */
    def starred(params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.starred(params))

    /** [[UserSocialApi.starredBy]] with its failure as a value. */
    def starredBy(username: Username, params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.starredBy(username, params))

    /** [[UserSocialApi.star]] with its failure as a value. */
    def star(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.star(owner, name))

    /** [[UserSocialApi.unstar]] with its failure as a value. */
    def unstar(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unstar(owner, name))

    /** [[UserSocialApi.isStarred]] with its failure as a value. */
    def isStarred(owner: Owner, name: RepoName): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.isStarred(owner, name))

    /** [[UserSocialApi.subscriptions]] with its failure as a value. */
    def subscriptions(params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.subscriptions(params))

    /** [[UserSocialApi.subscriptionsOf]] with its failure as a value. */
    def subscriptionsOf(username: Username, params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.subscriptionsOf(username, params))

    /** [[UserSocialApi.block]] with its failure as a value. */
    def block(username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.block(username))

    /** [[UserSocialApi.unblock]] with its failure as a value. */
    def unblock(username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unblock(username))

    /** [[UserSocialApi.blocked]] with its failure as a value. */
    def blocked(params: PageParams): Future[Either[CodebergError, Page[BlockedUser]]] =
      exec.attempt(rail.blocked(params))

    /** [[UserSocialApi.stopWatches]] with its failure as a value. */
    def stopWatches(params: PageParams): Future[Either[CodebergError, Page[StopWatch]]] =
      exec.attempt(rail.stopWatches(params))

    /** [[UserSocialApi.trackedTimes]] with its failure as a value. */
    def trackedTimes(
        window: TrackedTimeWindow,
        params: PageParams,
    ): Future[Either[CodebergError, Page[TrackedTime]]] =
      exec.attempt(rail.trackedTimes(window, params))

    /** [[UserSocialApi.activityFeeds]] with its failure as a value. */
    def activityFeeds(
        username: Username,
        query: ActivityFeedQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[RepositoryActivity]]] =
      exec.attempt(rail.activityFeeds(username, query, params))

    /** [[UserSocialApi.heatmap]] with its failure as a value. */
    def heatmap(username: Username): Future[Either[CodebergError, Vector[HeatmapEntry]]] =
      exec.attempt(rail.heatmap(username))

  private def followersRequest(params: PageParams): CodebergRequest =
    read(FollowersOperation, List("user", "followers"), SocialQueries.paging(params))

  private def followingRequest(params: PageParams): CodebergRequest =
    read(FollowingOperation, List("user", "following"), SocialQueries.paging(params))

  private def followRequest(username: Username): CodebergRequest =
    empty(FollowOperation, HttpMethod.Put, followingPath(username))

  private def unfollowRequest(username: Username): CodebergRequest =
    empty(UnfollowOperation, HttpMethod.Delete, followingPath(username))

  private def isFollowingRequest(username: Username): CodebergRequest =
    read(IsFollowingOperation, followingPath(username), Nil)

  private def followsRequest(username: Username, target: Username): CodebergRequest =
    read(FollowsOperation, List("users", username.value, "following", target.value), Nil)

  private def followRemoteRequest(target: RemoteFollowTarget): CodebergRequest =
    CodebergRequest(
      operation = FollowRemoteOperation,
      method    = HttpMethod.Post,
      path      = List("user", "activitypub", "follow"),
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Json(RemoteFollowOptionDto.render(target))),
    )

  private def starredRequest(params: PageParams): CodebergRequest =
    read(StarredOperation, List("user", "starred"), SocialQueries.paging(params))

  private def starredByRequest(username: Username, params: PageParams): CodebergRequest =
    read(StarredByOperation, List("users", username.value, "starred"), SocialQueries.paging(params))

  private def starRequest(owner: Owner, name: RepoName): CodebergRequest =
    empty(StarOperation, HttpMethod.Put, starredPath(owner, name))

  private def unstarRequest(owner: Owner, name: RepoName): CodebergRequest =
    empty(UnstarOperation, HttpMethod.Delete, starredPath(owner, name))

  private def isStarredRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(IsStarredOperation, starredPath(owner, name), Nil)

  private def subscriptionsRequest(params: PageParams): CodebergRequest =
    read(SubscriptionsOperation, List("user", "subscriptions"), SocialQueries.paging(params))

  private def subscriptionsOfRequest(username: Username, params: PageParams): CodebergRequest =
    read(SubscriptionsOfOperation, List("users", username.value, "subscriptions"), SocialQueries.paging(params))

  private def blockRequest(username: Username): CodebergRequest =
    empty(BlockOperation, HttpMethod.Put, List("user", "block", username.value))

  private def unblockRequest(username: Username): CodebergRequest =
    empty(UnblockOperation, HttpMethod.Put, List("user", "unblock", username.value))

  private def blockedRequest(params: PageParams): CodebergRequest =
    read(BlockedOperation, List("user", "list_blocked"), SocialQueries.paging(params))

  private def stopWatchesRequest(params: PageParams): CodebergRequest =
    read(StopWatchesOperation, List("user", "stopwatches"), SocialQueries.paging(params))

  private def trackedTimesRequest(window: TrackedTimeWindow, params: PageParams): CodebergRequest =
    read(
      TrackedTimesOperation,
      List("user", "times"),
      SocialQueries.trackedTimes(window) ++ SocialQueries.paging(params),
    )

  private def activityFeedsRequest(
      username: Username,
      query: ActivityFeedQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ActivityFeedsOperation,
      List("users", username.value, "activities", "feeds"),
      SocialQueries.activityFeeds(query) ++ SocialQueries.paging(params),
    )

  private def heatmapRequest(username: Username): CodebergRequest =
    read(HeatmapOperation, List("users", username.value, "heatmap"), Nil)

  private def followingPath(username: Username): List[String] =
    List("user", "following", username.value)

  private def starredPath(owner: Owner, name: RepoName): List[String] =
    List("user", "starred", owner.value, name.value)

  /** A `GET` with no body and no extra headers. */
  /** A mutating request whose whole meaning is its method and path.
    *
    * Every `PUT` and `DELETE` in this group is one of these: Forgejo takes the subject from the path and declares no
    * body at all, so nothing is sent and [[com.worxbend.codeberg4s.core.RequestBody.Empty]] would be a claim the spec
    * does not make.
    */
  private def empty(operation: String, method: HttpMethod, path: List[String]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = None,
    )
