package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.core.CodebergRequest.{empty, read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.repositories.hooks.wire.RepositoryFlagWire
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** A repository's administrative flags.
  *
  * Reached as `client.repos.flags`. Both error rails are here (ADR-0005), on the same terms as [[RepositoryHookApi]]:
  * the typed rail is derived from this one by [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot
  * disagree.
  *
  * ==What flags are, and why every call may be a 404==
  *
  * Repository flags are a Forgejo feature with no Gitea equivalent: an instance administrator attaches arbitrary named
  * markers to a repository, and instance policy keys off them. The whole surface is '''disabled by default''', and an
  * instance that has not enabled it answers `404` to every route here — the same status as a repository that does not
  * exist. There is no way to tell the two apart from the response, so a `404` from this class means "no flags here",
  * for one of three reasons: the repository is missing, it is invisible to these credentials, or the feature is off.
  *
  * Reading requires the repository to be visible; every write requires site administrator privileges and answers `403`
  * otherwise. See [[RepositoryFlag]].
  *
  * ==Evidence==
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response'''; see [[RepositoryHookApi]] for why no
  * fixture exists.
  *
  * ==Failures==
  *
  * The four remote failures are exactly those [[RepositoryHookApi]] lists, and are not repeated here. What is worth
  * repeating is that `403` is the ordinary answer to any write from a non-administrator, and that
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is never produced: a flag that could forge a path is rejected
  * by [[RepositoryFlag.from]] before a client is involved.
  *
  * ==Retries==
  *
  * The read is [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every write here is retried, and each
  * says why where it is defined — in short, every one of them names a repository, names a set of flags either
  * explicitly or by naming all of them, and states the end state it wants. None of them creates anything.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryFlagApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryFlagApi.Attempt = RepositoryFlagApi.Attempt(this)

  /** Lists a repository's flags — `GET /repos/{owner}/{repo}/flags`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit`, so the whole set
    * arrives at once and the result is a `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]] — a page
    * reporting a window nobody chose would be a lie about what was requested.
    *
    * '''The body is an array of bare strings''', with no object anywhere; a flag that cannot be a path segment fails
    * the whole listing at its own index rather than being dropped, because a flag this library cannot address is one
    * the caller could not then delete. See
    * [[com.worxbend.codeberg4s.repositories.hooks.wire.RepositoryFlagWire.toDomainAll]].
    *
    * '''Failures.''' The group contract above.
    */
  def list(owner: Owner, name: RepoName): Future[Vector[RepositoryFlag]] =
    pipeline.call(RepositoryFlagApi.listRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryHookDecoders.flags)

  /** Asks whether a repository carries a flag — `GET /repos/{owner}/{repo}/flags/{flag}`.
    *
    * '''This answers `204`, not a boolean.''' The route has no success body at all: a flag that is present is a `204`
    * and a flag that is absent is a `404`, so the question "does this repository have this flag?" is answered by
    * '''which rail the result arrives on''', not by a value. On the convenience rail a present flag completes the
    * `Future` and an absent one fails it; on [[RepositoryFlagApi.Attempt.check]] a present flag is a `Right(())` and an
    * absent one is a `Left` carrying a `404`.
    *
    * A caller who wants a `Boolean` should fold the typed rail themselves rather than have this library invent one —
    * because a `404` here has three possible causes (see the class note), and collapsing all of them to `false` would
    * report a repository that does not exist as one without the flag.
    *
    * '''Failures.''' The group contract above, with the `404` reading spelled out here.
    */
  def check(owner: Owner, name: RepoName, flag: RepositoryFlag): Future[Unit] =
    pipeline.callUnit(RepositoryFlagApi.checkRequest(owner, name, flag), RetryEligibility.IdempotentOnly)

  /** Replaces a repository's whole flag set — `PUT /repos/{owner}/{repo}/flags`.
    *
    * '''Retried''', because this is an assignment and not an increment: the request names one repository and states the
    * complete set of flags it should end up with, so applying it twice leaves exactly the state applying it once would,
    * and nothing is created. Unlike a delete there is no `404`-after-a-lost-success to warn about — the second attempt
    * addresses the repository, which is still there.
    *
    * '''An empty vector clears every flag''', which is the same end state [[deleteAll]] reaches, spelled as a
    * replacement.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def replaceAll(owner: Owner, name: RepoName, flags: Vector[RepositoryFlag]): Future[Unit] =
    pipeline.callUnit(RepositoryFlagApi.replaceAllRequest(owner, name, flags), RetryEligibility.AlwaysRetry)

  /** Removes every flag from a repository — `DELETE /repos/{owner}/{repo}/flags`.
    *
    * '''Retried''', for the reason [[replaceAll]] gives: "no flags" is a state, and reaching it twice is reaching it
    * once. This is the one delete in this class with no `404`-after-a-lost-success caveat, because what it addresses is
    * the repository rather than a flag — a repeat after a lost success answers `204` again.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteAll(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(RepositoryFlagApi.deleteAllRequest(owner, name), RetryEligibility.AlwaysRetry)

  /** Adds one flag to a repository — `PUT /repos/{owner}/{repo}/flags/{flag}`.
    *
    * '''Retried''', because it names one flag of one repository and asserts that it is present. Adding a flag that is
    * already there is a `204`, so a repeat after a lost success is indistinguishable from the first attempt succeeding
    * — which is exactly the property [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] asks for.
    *
    * '''The request carries an empty body rather than none at all''', because a `PUT` with no body is a request some
    * proxies and some servers treat differently from one with a zero-length body; see
    * [[com.worxbend.codeberg4s.core.RequestBody.Empty]]. The spec declares no body for this operation.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def add(owner: Owner, name: RepoName, flag: RepositoryFlag): Future[Unit] =
    pipeline.callUnit(RepositoryFlagApi.addRequest(owner, name, flag), RetryEligibility.AlwaysRetry)

  /** Removes one flag from a repository — `DELETE /repos/{owner}/{repo}/flags/{flag}`.
    *
    * '''Retried''', because it names one flag of one repository and asserts that it is absent; doing that twice leaves
    * the instance where doing it once would have, and creates nothing.
    *
    * '''Answers `204`''' whether or not the flag was there, so unlike [[RepositoryHookApi.delete]] there is no
    * `404`-after-a-lost-success reading to warn about: the `404` this route can answer is the class-level one about the
    * repository, not one about the flag.
    *
    * '''Failures.''' The group contract above.
    */
  def delete(owner: Owner, name: RepoName, flag: RepositoryFlag): Future[Unit] =
    pipeline.callUnit(RepositoryFlagApi.deleteRequest(owner, name, flag), RetryEligibility.AlwaysRetry)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryFlagApi:

  /** The stable operation id of [[RepositoryFlagApi.list]]. Safe to alert on. */
  val ListOperation: String = "repos.flags.list"

  /** The stable operation id of [[RepositoryFlagApi.check]]. */
  val CheckOperation: String = "repos.flags.check"

  /** The stable operation id of [[RepositoryFlagApi.replaceAll]]. */
  val ReplaceAllOperation: String = "repos.flags.replaceAll"

  /** The stable operation id of [[RepositoryFlagApi.deleteAll]]. */
  val DeleteAllOperation: String = "repos.flags.deleteAll"

  /** The stable operation id of [[RepositoryFlagApi.add]]. */
  val AddOperation: String = "repos.flags.add"

  /** The stable operation id of [[RepositoryFlagApi.delete]]. */
  val DeleteOperation: String = "repos.flags.delete"

  /** The typed rail of [[RepositoryFlagApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.flags.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryFlagApi)(using exec: Exec[Future]):

    /** [[RepositoryFlagApi.list]] with its failure as a value. */
    def list(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[RepositoryFlag]]] =
      exec.attempt(rail.list(owner, name))

    /** [[RepositoryFlagApi.check]] with its failure as a value — which is how the question is usefully asked; see
      * there.
      */
    def check(owner: Owner, name: RepoName, flag: RepositoryFlag): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.check(owner, name, flag))

    /** [[RepositoryFlagApi.replaceAll]] with its failure as a value. */
    def replaceAll(
        owner: Owner,
        name: RepoName,
        flags: Vector[RepositoryFlag],
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.replaceAll(owner, name, flags))

    /** [[RepositoryFlagApi.deleteAll]] with its failure as a value. */
    def deleteAll(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteAll(owner, name))

    /** [[RepositoryFlagApi.add]] with its failure as a value. */
    def add(owner: Owner, name: RepoName, flag: RepositoryFlag): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.add(owner, name, flag))

    /** [[RepositoryFlagApi.delete]] with its failure as a value. */
    def delete(owner: Owner, name: RepoName, flag: RepositoryFlag): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(owner, name, flag))

  private def listRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListOperation, flagsPath(owner, name), Nil)

  private def checkRequest(owner: Owner, name: RepoName, flag: RepositoryFlag): CodebergRequest =
    read(CheckOperation, flagPath(owner, name, flag), Nil)

  private def replaceAllRequest(owner: Owner, name: RepoName, flags: Vector[RepositoryFlag]): CodebergRequest =
    write(
      ReplaceAllOperation,
      HttpMethod.Put,
      flagsPath(owner, name),
      RepositoryFlagWire.renderReplace(flags),
    )

  private def deleteAllRequest(owner: Owner, name: RepoName): CodebergRequest =
    remove(DeleteAllOperation, flagsPath(owner, name))

  private def addRequest(owner: Owner, name: RepoName, flag: RepositoryFlag): CodebergRequest =
    empty(AddOperation, HttpMethod.Put, flagPath(owner, name, flag))

  private def deleteRequest(owner: Owner, name: RepoName, flag: RepositoryFlag): CodebergRequest =
    remove(DeleteOperation, flagPath(owner, name, flag))

  private def flagsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "flags"

  private def flagPath(owner: Owner, name: RepoName, flag: RepositoryFlag): List[String] =
    flagsPath(owner, name) :+ flag.value
