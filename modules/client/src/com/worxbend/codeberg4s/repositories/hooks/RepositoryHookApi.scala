package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.hooks.wire.{HookOptionDto, HookQueries}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** A repository's hooks: the webhooks Forgejo delivers to somewhere else, and the Git hooks it runs on its own machine.
  *
  * Reached as `client.repos.hooks`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryHookApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Two unrelated things share one route prefix==
  *
  * `/repos/{owner}/{repo}/hooks` addresses '''webhooks''' — HTTP deliveries Forgejo makes to a URL a caller chose — and
  * `/repos/{owner}/{repo}/hooks/git` addresses '''Git hooks''' — shell scripts the instance executes when a push
  * arrives. They share nothing but the prefix: different models ([[Webhook]] versus [[GitHook]]), different identifiers
  * ([[HookId]] versus [[GitHookName]]), and different privilege requirements. The Git hook routes are administrative —
  * Forgejo restricts them to site administrators and refuses them outright on an instance that has disabled custom Git
  * hooks — which arrives as a `403`.
  *
  * ==Evidence==
  *
  * '''Every model in this group is derived from `spec/swagger.v1.json`, not from a captured response.''' The harvest
  * behind `modules/codec/test/resources/golden` was anonymous and every route here requires a token, so no fixture
  * exists for any of them. The field sets and the nullability treatment are the spec read literally under the rule
  * `docs/HAZARDS.md` §1 forces on the whole API. Where a shape is asserted in a test, the payload was written by hand
  * to match that definition — it is not evidence that Forgejo sends exactly this.
  *
  * ==Secrets==
  *
  * A webhook carries two credentials, and neither one ever comes back. [[create]] and [[edit]] take a [[HookSecret]],
  * which masks itself in every rendering path; [[HookConfig]] refuses to hold either of them in either direction, so
  * nothing an instance echoes back can reach a caller, a log line or a
  * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] snippet. See both types for why that is modelled rather
  * than documented.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — and `404`
  *     again for a hook that does not exist in it. `401` when a token was required and none was sent, and `403` when
  *     the token lacks the scope or the route is administrative. `422` '''and''' `400` both mean the request was
  *     rejected as invalid; `docs/HAZARDS.md` §4 records Forgejo using `400` where a reader would expect `422`.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path is rejected by its own smart constructor before a
  * client is ever involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Writes are decided per endpoint and each
  * decision is justified where it is made; in summary, [[create]] and [[test]] are never retried and everything else
  * that writes is, because it names one hook and states the value it wants that hook to have.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryHookApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryHookApi.Attempt = RepositoryHookApi.Attempt(this)

  // --- webhooks -------------------------------------------------------------

  /** Lists a repository's webhooks — `GET /repos/{owner}/{repo}/hooks`.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''No secret is in the result.''' Whatever an instance sends under `secret` or `authorization_header` is dropped
    * before a [[Webhook]] exists; see [[HookConfig]].
    *
    * '''Failures.''' The group contract above.
    */
  def list(owner: Owner, name: RepoName, params: PageParams): Future[Page[Webhook]] =
    pipeline.callPage(RepositoryHookApi.listRequest(owner, name, params), params)(using RepositoryHookDecoders.webhooks)

  /** Reads one webhook — `GET /repos/{owner}/{repo}/hooks/{id}`.
    *
    * '''Failures.''' The group contract above. `404` covers both "no such hook in this repository" and "no such
    * repository", which are indistinguishable from the response alone.
    */
  def get(owner: Owner, name: RepoName, id: HookId): Future[Webhook] =
    pipeline.call(RepositoryHookApi.getRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryHookDecoders.webhook)

  /** Creates a webhook — `POST /repos/{owner}/{repo}/hooks`.
    *
    * '''Never retried.''' Nothing in the request identifies it: hook URLs are not unique, Forgejo offers no idempotency
    * key, and a repeat therefore creates a '''second''' hook pointing at the same endpoint, which then receives every
    * delivery twice. A transport failure leaves the caller genuinely unsure whether a hook exists, which is the honest
    * state of affairs and better than two — [[list]] resolves it.
    *
    * '''Answers `201` with the created hook''', including the identifier every other route on this class needs.
    *
    * '''Failures.''' The group contract above. A `422` is what Forgejo answers for a config it will not accept — most
    * often a `url` it cannot parse or a `type` it does not implement.
    */
  def create(owner: Owner, name: RepoName, command: CreateHook): Future[Webhook] =
    pipeline.call(RepositoryHookApi.createRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryHookDecoders.webhook)

  /** Edits a webhook — `PATCH /repos/{owner}/{repo}/hooks/{id}`.
    *
    * '''Retried''', and the argument is the one earlier waves set the bar with: the request names one instance-wide
    * identifier that Forgejo never reuses — [[HookId]] is a database row id — and states the value it wants each key it
    * mentions to have. Applying that twice leaves the hook exactly where applying it once would, and creates nothing.
    * The one cost is the usual one: if the first attempt succeeded and its response was lost, a retry addresses a hook
    * that is already in the requested state and simply reports it again, so unlike a delete there is not even a `404`
    * to explain.
    *
    * '''What the command does not mention is left alone'''; see [[EditHook]] for how "unsubscribe from everything" is
    * spelled differently from "leave the subscriptions alone".
    *
    * '''Failures.''' The group contract above.
    */
  def edit(owner: Owner, name: RepoName, id: HookId, command: EditHook): Future[Webhook] =
    pipeline.call(RepositoryHookApi.editRequest(owner, name, id, command), RetryEligibility.AlwaysRetry)(using
      RepositoryHookDecoders.webhook)

  /** Deletes a webhook — `DELETE /repos/{owner}/{repo}/hooks/{id}`.
    *
    * '''Retried''', because deleting a resource named by an identifier the server never reuses is idempotent: doing it
    * twice leaves the instance where doing it once would have, and nothing is created. The cost a caller has to know:
    * if the first attempt succeeded and its response was lost, the retry addresses something that no longer exists and
    * answers `404`. A `404` from this call therefore means "it is gone", not necessarily "it was never there".
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def delete(owner: Owner, name: RepoName, id: HookId): Future[Unit] =
    pipeline.callUnit(RepositoryHookApi.deleteRequest(owner, name, id), RetryEligibility.AlwaysRetry)

  /** Fires a test delivery at a webhook — `POST /repos/{owner}/{repo}/hooks/{id}/tests`.
    *
    * '''Never retried, and not because of Forgejo.''' This call causes a real HTTP request to leave the instance and
    * arrive at a '''third party''' — the endpoint the hook points at, which is a system this library knows nothing
    * about and whose idempotency it cannot reason about. A repeat delivers a second payload that the receiving system
    * may act on: a second deployment, a second alert, a second chat message. That the forge's own state is unchanged is
    * beside the point; the side effect is somewhere else, and it is not this library's to gamble with. A caller who
    * knows their receiver is safe to hit twice can call this again themselves.
    *
    * '''Answers `204`''', and says nothing about what the receiver did with the delivery — the endpoint reports that
    * Forgejo sent it, not that anyone accepted it.
    *
    * '''Only push deliveries.''' The spec titles the operation "Test a push webhook": Forgejo builds a synthetic push
    * payload for the commit `ref` names, whatever the hook's actual subscriptions are.
    *
    * '''Failures.''' The group contract above. A `ref` naming nothing gets a `404`, the same status as a missing hook.
    *
    * @param ref
    *   the branch, tag or commit whose head is loaded into the synthetic payload; `None` lets Forgejo choose
    */
  def test(owner: Owner, name: RepoName, id: HookId, ref: Option[String]): Future[Unit] =
    pipeline.callUnit(RepositoryHookApi.testRequest(owner, name, id, ref), RetryEligibility.Never)

  // --- Git hooks ------------------------------------------------------------

  /** Lists a repository's Git hooks — `GET /repos/{owner}/{repo}/hooks/git`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit` for this operation,
    * so the whole list arrives at once and the result is a `Vector` rather than a
    * [[com.worxbend.codeberg4s.paging.Page]] — a page reporting a window nobody chose would be a lie about what was
    * requested. The list is bounded by Git's own hook vocabulary and has three entries on current Forgejo, so this is
    * not the unbounded read that would make paging necessary.
    *
    * '''Administrative.''' See the class note; a non-administrator gets a `403`.
    *
    * '''Failures.''' The group contract above.
    */
  def listGitHooks(owner: Owner, name: RepoName): Future[Vector[GitHook]] =
    pipeline.call(RepositoryHookApi.listGitHooksRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryHookDecoders.gitHooks)

  /** Reads one Git hook — `GET /repos/{owner}/{repo}/hooks/git/{id}`.
    *
    * '''Failures.''' The group contract above. A name Git does not know is a `404`, as is a repository that does not
    * exist.
    */
  def gitHook(owner: Owner, name: RepoName, hook: GitHookName): Future[GitHook] =
    pipeline.call(RepositoryHookApi.gitHookRequest(owner, name, hook), RetryEligibility.IdempotentOnly)(using
      RepositoryHookDecoders.gitHook)

  /** Installs or replaces a Git hook's script — `PATCH /repos/{owner}/{repo}/hooks/git/{id}`.
    *
    * '''Retried''', on the same terms as [[edit]] and with one difference worth stating: the name this addresses —
    * `pre-receive`, `update`, `post-receive` — is a fixed slot rather than an identifier the server allocates, so it is
    * not "never reused" in the sense a row id is. It does not need to be. The request assigns a stated script to a
    * stated slot of a stated repository, so applying it twice leaves exactly the state applying it once would, and
    * creates nothing.
    *
    * '''This installs code the instance will execute.''' See [[EditGitHook]] for why nothing here validates it.
    *
    * '''Failures.''' The group contract above.
    */
  def editGitHook(owner: Owner, name: RepoName, hook: GitHookName, command: EditGitHook): Future[GitHook] =
    pipeline.call(RepositoryHookApi.editGitHookRequest(owner, name, hook, command), RetryEligibility.AlwaysRetry)(using
      RepositoryHookDecoders.gitHook)

  /** Clears a Git hook's script — `DELETE /repos/{owner}/{repo}/hooks/git/{id}`.
    *
    * '''Retried''', for the reason [[editGitHook]] gives: this empties a stated slot, and emptying it twice is emptying
    * it once. Unlike [[delete]] there is no `404`-after-a-lost-success to warn about, because the slot itself survives
    * — what is deleted is its content.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteGitHook(owner: Owner, name: RepoName, hook: GitHookName): Future[Unit] =
    pipeline.callUnit(RepositoryHookApi.deleteGitHookRequest(owner, name, hook), RetryEligibility.AlwaysRetry)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryHookApi:

  /** The stable operation id of [[RepositoryHookApi.list]]. Safe to alert on. */
  val ListOperation: String = "repos.hooks.list"

  /** The stable operation id of the single-webhook read on [[RepositoryHookApi]]. */
  val GetOperation: String = "repos.hooks.get"

  /** The stable operation id of [[RepositoryHookApi.create]]. */
  val CreateOperation: String = "repos.hooks.create"

  /** The stable operation id of [[RepositoryHookApi.edit]]. */
  val EditOperation: String = "repos.hooks.edit"

  /** The stable operation id of [[RepositoryHookApi.delete]]. */
  val DeleteOperation: String = "repos.hooks.delete"

  /** The stable operation id of [[RepositoryHookApi.test]]. */
  val TestOperation: String = "repos.hooks.test"

  /** The stable operation id of [[RepositoryHookApi.listGitHooks]]. */
  val ListGitHooksOperation: String = "repos.hooks.git.list"

  /** The stable operation id of the single-Git-hook read on [[RepositoryHookApi]]. */
  val GetGitHookOperation: String = "repos.hooks.git.get"

  /** The stable operation id of [[RepositoryHookApi.editGitHook]]. */
  val EditGitHookOperation: String = "repos.hooks.git.edit"

  /** The stable operation id of [[RepositoryHookApi.deleteGitHook]]. */
  val DeleteGitHookOperation: String = "repos.hooks.git.delete"

  /** The typed rail of [[RepositoryHookApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.hooks.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryHookApi)(using exec: Exec[Future]):

    /** [[RepositoryHookApi.list]] with its failure as a value. */
    def list(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[Webhook]]] =
      exec.attempt(rail.list(owner, name, params))

    /** The single-webhook read on [[RepositoryHookApi]], with its failure as a value. */
    def get(owner: Owner, name: RepoName, id: HookId): Future[Either[CodebergError, Webhook]] =
      exec.attempt(rail.get(owner, name, id))

    /** [[RepositoryHookApi.create]] with its failure as a value. */
    def create(owner: Owner, name: RepoName, command: CreateHook): Future[Either[CodebergError, Webhook]] =
      exec.attempt(rail.create(owner, name, command))

    /** [[RepositoryHookApi.edit]] with its failure as a value. */
    def edit(
        owner: Owner,
        name: RepoName,
        id: HookId,
        command: EditHook,
    ): Future[Either[CodebergError, Webhook]] =
      exec.attempt(rail.edit(owner, name, id, command))

    /** [[RepositoryHookApi.delete]] with its failure as a value. */
    def delete(owner: Owner, name: RepoName, id: HookId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(owner, name, id))

    /** [[RepositoryHookApi.test]] with its failure as a value. */
    def test(
        owner: Owner,
        name: RepoName,
        id: HookId,
        ref: Option[String],
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.test(owner, name, id, ref))

    /** [[RepositoryHookApi.listGitHooks]] with its failure as a value. */
    def listGitHooks(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[GitHook]]] =
      exec.attempt(rail.listGitHooks(owner, name))

    /** The single-Git-hook read on [[RepositoryHookApi]], with its failure as a value. */
    def gitHook(owner: Owner, name: RepoName, hook: GitHookName): Future[Either[CodebergError, GitHook]] =
      exec.attempt(rail.gitHook(owner, name, hook))

    /** [[RepositoryHookApi.editGitHook]] with its failure as a value. */
    def editGitHook(
        owner: Owner,
        name: RepoName,
        hook: GitHookName,
        command: EditGitHook,
    ): Future[Either[CodebergError, GitHook]] =
      exec.attempt(rail.editGitHook(owner, name, hook, command))

    /** [[RepositoryHookApi.deleteGitHook]] with its failure as a value. */
    def deleteGitHook(owner: Owner, name: RepoName, hook: GitHookName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteGitHook(owner, name, hook))

  private def listRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListOperation, hooksPath(owner, name), HookQueries.paging(params))

  private def getRequest(owner: Owner, name: RepoName, id: HookId): CodebergRequest =
    read(GetOperation, hookPath(owner, name, id), Nil)

  private def createRequest(owner: Owner, name: RepoName, command: CreateHook): CodebergRequest =
    write(
      CreateOperation,
      HttpMethod.Post,
      hooksPath(owner, name),
      HookOptionDto.renderCreate(command),
    )

  private def editRequest(owner: Owner, name: RepoName, id: HookId, command: EditHook): CodebergRequest =
    write(
      EditOperation,
      HttpMethod.Patch,
      hookPath(owner, name, id),
      HookOptionDto.renderEdit(command),
    )

  private def deleteRequest(owner: Owner, name: RepoName, id: HookId): CodebergRequest =
    remove(DeleteOperation, hookPath(owner, name, id))

  private def testRequest(owner: Owner, name: RepoName, id: HookId, ref: Option[String]): CodebergRequest =
    bodiless(TestOperation, HttpMethod.Post, hookPath(owner, name, id) :+ "tests", HookQueries.hookTest(ref))

  private def listGitHooksRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListGitHooksOperation, gitHooksPath(owner, name), Nil)

  private def gitHookRequest(owner: Owner, name: RepoName, hook: GitHookName): CodebergRequest =
    read(GetGitHookOperation, gitHookPath(owner, name, hook), Nil)

  private def editGitHookRequest(
      owner: Owner,
      name: RepoName,
      hook: GitHookName,
      command: EditGitHook,
  ): CodebergRequest =
    write(
      EditGitHookOperation,
      HttpMethod.Patch,
      gitHookPath(owner, name, hook),
      HookOptionDto.renderEditGit(command),
    )

  private def deleteGitHookRequest(owner: Owner, name: RepoName, hook: GitHookName): CodebergRequest =
    remove(DeleteGitHookOperation, gitHookPath(owner, name, hook))

  private def hooksPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "hooks"

  private def hookPath(owner: Owner, name: RepoName, id: HookId): List[String] =
    hooksPath(owner, name) :+ id.value.toString

  private def gitHooksPath(owner: Owner, name: RepoName): List[String] =
    hooksPath(owner, name) :+ "git"

  private def gitHookPath(owner: Owner, name: RepoName, hook: GitHookName): List[String] =
    gitHooksPath(owner, name) :+ hook.value
