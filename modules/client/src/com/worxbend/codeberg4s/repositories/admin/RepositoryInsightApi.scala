package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.issues.wire.IssueQueries
import com.worxbend.codeberg4s.issues.{Issue, TrackedTime, TrackedTimeQuery}
import com.worxbend.codeberg4s.miscellaneous.SigningKey
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.admin.wire.AdminQueries
import com.worxbend.codeberg4s.users.Username
import com.worxbend.codeberg4s.{CodebergError, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

import java.time.LocalDate

/** What a repository can tell you about itself: its activity, its languages, its pins, its topics and its tracked time.
  *
  * Reached as `client.repos.admin.insights`. It is a group of its own rather than more methods on
  * [[RepositoryAdminApi]] because that class had grown past what a reader can hold in their head; the endpoints, the
  * models and the retry decisions are unchanged by the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryInsightApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Reads only==
  *
  * Nothing here changes anything. Every operation is a `GET` reporting on work somebody else has done, which is why the
  * group is separate from the administration that does the changing.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope or the account lacks the
  *     permission. `422` '''and''' `400` both mean the request was rejected as invalid; `docs/HAZARDS.md` §4 records
  *     Forgejo using `400` where a reader would expect `422`.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path or a query parameter is rejected by its own smart
  * constructor before a client is ever involved.
  *
  * ==Retries==
  *
  * Every operation here uses [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]: they are all reads, and
  * a repeated read costs nothing but the round trip.
  *
  * ==Evidence==
  *
  * '''Every model this group declares is derived from `spec/swagger.v1.json`, not from a captured response.''' The
  * harvest behind `modules/codec/test/resources/golden` was anonymous and every endpoint here requires a token, so no
  * fixture exists for any of them. Where a shape is asserted in a test, the payload was written by hand to match the
  * spec's definition — it is not evidence that Forgejo sends exactly this.
  */

final class RepositoryInsightApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryInsightApi.Attempt = RepositoryInsightApi.Attempt(this)

  /** Lists the repository's activity feed — `GET /repos/{owner}/{repo}/activities/feeds`.
    *
    * '''Paging.''' As [[pushMirrors]]: the `Link` header decides.
    *
    * '''Failures.''' The group contract above.
    *
    * @param date
    *   restrict the feed to one calendar day. A `java.time.LocalDate` rather than an instant because the spec declares
    *   the parameter `format: date` — see [[com.worxbend.codeberg4s.repositories.admin.wire.AdminQueries.activities]]
    *   for why a time zone must not be chosen on the caller's behalf
    */
  def activityFeed(
      owner: Owner,
      name: RepoName,
      date: Option[LocalDate],
      params: PageParams,
  ): Future[Page[RepositoryActivity]] =
    pipeline.callPage(RepositoryInsightApi.activityFeedRequest(owner, name, date, params), params)(using
      RepositoryAdminDecoders.activities)

  /** Reads how many bytes of each language the repository holds — `GET /repos/{owner}/{repo}/languages`.
    *
    * '''The body is a bare object with no fixed keys''', which is why it has a model of its own; see
    * [[LanguageBreakdown]]. A repository whose analysis has not run answers `{}`, which arrives as
    * [[LanguageBreakdown.Empty]] and is a success.
    *
    * '''Failures.''' The group contract above.
    */
  def languages(owner: Owner, name: RepoName): Future[LanguageBreakdown] =
    pipeline.call(RepositoryInsightApi.languagesRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.languages)

  /** Reads whether another issue or pull request may be pinned — `GET /repos/{owner}/{repo}/new_pin_allowed`.
    *
    * The check to make before attempting a pin, since the cap is an instance setting the caller cannot read otherwise.
    *
    * '''Failures.''' The group contract above.
    */
  def newPinAllowed(owner: Owner, name: RepoName): Future[IssuePinsAllowed] =
    pipeline.call(RepositoryInsightApi.newPinAllowedRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.issuePinsAllowed)

  /** Lists the repository's pinned issues — `GET /repos/{owner}/{repo}/issues/pinned`.
    *
    * '''Not paged''': the endpoint answers the whole set, which is small by construction because Forgejo caps it. The
    * order is the pin order the repository's maintainers chose, not the issue order.
    *
    * '''Failures.''' The group contract above.
    */
  def pinnedIssues(owner: Owner, name: RepoName): Future[Vector[Issue]] =
    pipeline.call(RepositoryInsightApi.pinnedIssuesRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.issues)

  /** Reads the key the repository's commits are signed with — `GET /repos/{owner}/{repo}/signing-key.gpg`.
    *
    * '''Not JSON.''' The response is an armored OpenPGP block under `text/plain`, so nothing parses it — see
    * [[com.worxbend.codeberg4s.miscellaneous.PlainText]]. A repository that signs nothing answers `200` with an empty
    * body, which is `None` here and a success, not a failure.
    *
    * '''Failures.''' The group contract above, minus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]], which
    * this method cannot produce because nothing is parsed.
    */
  def signingKey(owner: Owner, name: RepoName): Future[Option[SigningKey]] =
    pipeline.call(RepositoryInsightApi.signingKeyRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.signingKey)

  /** Lists the time recorded against the repository's issues — `GET /repos/{owner}/{repo}/times`.
    *
    * '''Time tracking has to be on.''' A repository whose `internal_tracker.enable_time_tracker` is `false` answers
    * `404` here, which is indistinguishable from a missing repository; [[EditRepository.trackingIssuesWith]] is what
    * turns it on.
    *
    * '''Paging.''' As [[pushMirrors]].
    *
    * '''Failures.''' The group contract above. `403` is what filtering by another account produces when the caller is
    * not an issue manager — see [[com.worxbend.codeberg4s.issues.TrackedTimeQuery]].
    *
    * @param query
    *   the filters to apply; [[com.worxbend.codeberg4s.issues.TrackedTimeQuery.Empty]] asks for every entry
    */
  def trackedTimes(
      owner: Owner,
      name: RepoName,
      query: TrackedTimeQuery,
      params: PageParams,
  ): Future[Page[TrackedTime]] =
    pipeline.callPage(RepositoryInsightApi.trackedTimesRequest(owner, name, query, params), params)(using
      RepositoryAdminDecoders.trackedTimes)

  /** Lists one account's tracked time in the repository — `GET /repos/{owner}/{repo}/times/{user}`.
    *
    * '''Not paged''', unlike [[trackedTimes]] — the spec's own name for the response is
    * `TrackedTimeListWithoutPagination`, and it sends no paging headers. This is a genuine difference between two
    * endpoints that otherwise answer the same model, not an oversight here.
    *
    * '''Failures.''' The group contract above.
    */
  def trackedTimesFor(owner: Owner, name: RepoName, user: Username): Future[Vector[TrackedTime]] =
    pipeline.call(RepositoryInsightApi.trackedTimesForRequest(owner, name, user), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.trackedTimes)

  /** Searches the instance's topics — `GET /topics/search`.
    *
    * Instance-wide, not repository-scoped: this is how a caller discovers what topics exist before setting one with
    * `RepositoryPublishingApi`'s topic endpoints.
    *
    * '''The body is a `{"topics": [...]}` envelope''', the third envelope shape in this library; see
    * [[com.worxbend.codeberg4s.repositories.admin.wire.TopicSearchEnvelopeDto]].
    *
    * '''Paging.''' As [[pushMirrors]].
    *
    * '''Failures.''' The group contract above.
    *
    * @param keyword
    *   the search term, which the spec marks required
    */
  def searchTopics(keyword: String, params: PageParams): Future[Page[TopicSummary]] =
    pipeline.callPage(RepositoryInsightApi.searchTopicsRequest(keyword, params), params)(using
      RepositoryAdminDecoders.topics)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryInsightApi:

  /** The stable operation id of [[RepositoryInsightApi.activityFeed]]. */
  val ListActivityFeedOperation: String = "repos.admin.activities.list"

  /** The stable operation id of [[RepositoryInsightApi.languages]]. */
  val GetLanguagesOperation: String = "repos.admin.languages.get"

  /** The stable operation id of [[RepositoryInsightApi.newPinAllowed]]. */
  val NewPinAllowedOperation: String = "repos.admin.pins.allowed"

  /** The stable operation id of [[RepositoryInsightApi.pinnedIssues]]. */
  val ListPinnedIssuesOperation: String = "repos.admin.pins.issues"

  /** The stable operation id of [[RepositoryInsightApi.signingKey]]. */
  val SigningKeyOperation: String = "repos.admin.signingKey"

  /** The stable operation id of [[RepositoryInsightApi.trackedTimes]]. */
  val ListTrackedTimesOperation: String = "repos.admin.times.list"

  /** The stable operation id of [[RepositoryInsightApi.trackedTimesFor]]. */
  val UserTrackedTimesOperation: String = "repos.admin.times.user"

  /** The stable operation id of [[RepositoryInsightApi.searchTopics]]. */
  val SearchTopicsOperation: String = "repos.admin.topics.search"

  /** The typed rail of [[RepositoryInsightApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.admin.insights.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryInsightApi)(using exec: Exec[Future]):

    /** [[RepositoryInsightApi.activityFeed]] with its failure as a value. */
    def activityFeed(
        owner: Owner,
        name: RepoName,
        date: Option[LocalDate],
        params: PageParams,
    ): Future[Either[CodebergError, Page[RepositoryActivity]]] =
      exec.attempt(rail.activityFeed(owner, name, date, params))

    /** [[RepositoryInsightApi.languages]] with its failure as a value. */
    def languages(owner: Owner, name: RepoName): Future[Either[CodebergError, LanguageBreakdown]] =
      exec.attempt(rail.languages(owner, name))

    /** [[RepositoryInsightApi.newPinAllowed]] with its failure as a value. */
    def newPinAllowed(owner: Owner, name: RepoName): Future[Either[CodebergError, IssuePinsAllowed]] =
      exec.attempt(rail.newPinAllowed(owner, name))

    /** [[RepositoryInsightApi.pinnedIssues]] with its failure as a value. */
    def pinnedIssues(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[Issue]]] =
      exec.attempt(rail.pinnedIssues(owner, name))

    /** [[RepositoryInsightApi.signingKey]] with its failure as a value. */
    def signingKey(owner: Owner, name: RepoName): Future[Either[CodebergError, Option[SigningKey]]] =
      exec.attempt(rail.signingKey(owner, name))

    /** [[RepositoryInsightApi.trackedTimes]] with its failure as a value. */
    def trackedTimes(
        owner: Owner,
        name: RepoName,
        query: TrackedTimeQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[TrackedTime]]] =
      exec.attempt(rail.trackedTimes(owner, name, query, params))

    /** [[RepositoryInsightApi.trackedTimesFor]] with its failure as a value. */
    def trackedTimesFor(
        owner: Owner,
        name: RepoName,
        user: Username,
    ): Future[Either[CodebergError, Vector[TrackedTime]]] =
      exec.attempt(rail.trackedTimesFor(owner, name, user))

    /** [[RepositoryInsightApi.searchTopics]] with its failure as a value. */
    def searchTopics(keyword: String, params: PageParams): Future[Either[CodebergError, Page[TopicSummary]]] =
      exec.attempt(rail.searchTopics(keyword, params))

  private def activityFeedRequest(
      owner: Owner,
      name: RepoName,
      date: Option[LocalDate],
      params: PageParams,
  ): CodebergRequest =
    read(
      ListActivityFeedOperation,
      RepositoryRequests.repositoryPath(owner, name) ++ List("activities", "feeds"),
      AdminQueries.activities(date) ++ PagingQuery.window(params),
    )

  private def languagesRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(GetLanguagesOperation, RepositoryRequests.repositoryPath(owner, name) :+ "languages", Nil)

  private def newPinAllowedRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(NewPinAllowedOperation, RepositoryRequests.repositoryPath(owner, name) :+ "new_pin_allowed", Nil)

  private def pinnedIssuesRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListPinnedIssuesOperation, RepositoryRequests.repositoryPath(owner, name) ++ List("issues", "pinned"), Nil)

  private def signingKeyRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(SigningKeyOperation, RepositoryRequests.repositoryPath(owner, name) :+ "signing-key.gpg", Nil)

  private def trackedTimesRequest(
      owner: Owner,
      name: RepoName,
      query: TrackedTimeQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListTrackedTimesOperation,
      timesPath(owner, name),
      IssueQueries.trackedTimes(query) ++ PagingQuery.window(params),
    )

  private def trackedTimesForRequest(owner: Owner, name: RepoName, user: Username): CodebergRequest =
    read(UserTrackedTimesOperation, timesPath(owner, name) :+ user.value, Nil)

  private def searchTopicsRequest(keyword: String, params: PageParams): CodebergRequest =
    read(
      SearchTopicsOperation,
      List("topics", "search"),
      AdminQueries.topicSearch(keyword) ++ PagingQuery.window(params),
    )

  private def timesPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "times"
