package com.worxbend.codeberg4s.users

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto
import com.worxbend.codeberg4s.users.account.UserAccountApi
import com.worxbend.codeberg4s.users.account.UserActionApi
import com.worxbend.codeberg4s.users.account.UserApplicationApi
import com.worxbend.codeberg4s.users.account.UserHookApi
import com.worxbend.codeberg4s.users.account.UserQuotaApi
import com.worxbend.codeberg4s.users.social.UserKeyApi
import com.worxbend.codeberg4s.users.social.UserSocialApi
import com.worxbend.codeberg4s.users.social.UserTokenApi
import com.worxbend.codeberg4s.users.wire.PublicKeyDto
import com.worxbend.codeberg4s.users.wire.UserDto
import com.worxbend.codeberg4s.wire.SearchEnvelopeDto

import scala.concurrent.Future

/** User endpoints — the current account, accounts by name, and what an account owns or follows.
  *
  * Reached as `client.users`. Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[UserApi.attempt]] never fail and return
  * an `Either` instead. The typed rail is derived from this one by [[com.worxbend.codeberg4s.core.Exec.attempt]], so
  * the two cannot disagree about what an operation does.
  *
  * '''Two families of path.''' `/user/…` means "whoever the configured credentials are" and needs a token;
  * `/users/{username}/…` names an account explicitly and the spec marks it anonymous. The spec is not to be trusted on
  * that second point — `docs/HAZARDS.md` §2 measures that '''no''' operation in the document carries per-endpoint
  * security information at all, and the fixture manifest records that codeberg.org answers `401` to an anonymous
  * `/users/{u}/followers`. Configure a token unless a call is known to work without one on the instance being talked
  * to.
  *
  * '''Pagination.''' Every listing operation takes a [[com.worxbend.codeberg4s.paging.PageParams]] and returns one
  * [[com.worxbend.codeberg4s.paging.Page]]; none of them fetches the whole collection. `page` and `limit` are always
  * sent together, because the fixture manifest records list endpoints that ignore `limit` on its own and return
  * everything. Whether more pages exist is decided by the response's `rel="next"` link and never by how many items came
  * back — Forgejo clamps `limit` to its own maximum while echoing the requested value.
  *
  * Names are [[Username]] rather than `String`, so a value that would forge a request path is rejected by
  * [[Username.from]] before a client is ever involved — which is why no operation here produces
  * [[com.worxbend.codeberg4s.CodebergError.Validation]].
  */
final class UserApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: UserApi.Attempt = UserApi.Attempt(this)

  /** The authenticated account itself: settings, avatar, email addresses, repositories and teams. */
  val account: UserAccountApi = UserAccountApi(pipeline)

  /** Forgejo Actions scoped to the authenticated user. */
  val actions: UserActionApi = UserActionApi(pipeline)

  /** OAuth2 applications the authenticated user owns. */
  val applications: UserApplicationApi = UserApplicationApi(pipeline)

  /** Webhooks the authenticated user owns. */
  val hooks: UserHookApi = UserHookApi(pipeline)

  /** Storage quota for the authenticated user. */
  val quota: UserQuotaApi = UserQuotaApi(pipeline)

  /** Follows, stars, watches, blocks, activity and the contribution heatmap. */
  val social: UserSocialApi = UserSocialApi(pipeline)

  /** SSH and GPG keys, including the GPG verification handshake. */
  val keys: UserKeyApi = UserKeyApi(pipeline)

  /** Access tokens. Creating one returns its only readable copy. */
  val tokens: UserTokenApi = UserTokenApi(pipeline)

  /** Reads the account the configured credentials belong to — `GET /user`.
    *
    * This is the cheapest way to check that a token is valid and to learn which account it acts as, which is not
    * otherwise derivable: a Forgejo token carries no readable subject.
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when no credentials were configured or the token
    * was rejected — this endpoint has no anonymous reading, and `golden/error/401-token-required.json` is exactly what
    * comes back — and `403` when the token is valid but lacks the scope.
    * [[com.worxbend.codeberg4s.CodebergError.Transport]] means nothing reached the instance,
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means the payload carried no `id` or `login`, and
    * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] means a retryable failure outlived the policy. `GET` is
    * safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def current(): Future[User] =
    pipeline.call(UserApi.currentRequest, RetryEligibility.IdempotentOnly)(using UserApi.UserDecoder)

  /** Reads one account by name — `GET /users/{username}`.
    *
    * An organisation answers here too, field for field as a [[User]]: `golden/user/user-single-org-shaped.json` is
    * `GET /users/forgejo`, and the payload carries nothing that distinguishes it from a person. The distinction lives
    * in the endpoint, not in the body.
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when no such account exists '''or''' the account
    * is not visible to the configured credentials — Forgejo does not distinguish the two, and the body it sends is
    * `golden/error/404-user-not-found.json`, whose `message` is Forgejo's internal wording (`user redirect does not
    * exist [name: …]`) and whose `url` is the useless constant every error carries. `401` and `403` are possible on an
    * instance that requires sign-in to browse accounts. [[com.worxbend.codeberg4s.CodebergError.Transport]],
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] and
    * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] are as for [[current]]. `GET` is safe, so the call is
    * retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param username
    *   the account handle as it appears in a Codeberg URL
    */
  def get(username: Username): Future[User] =
    pipeline.call(UserApi.getRequest(username), RetryEligibility.IdempotentOnly)(using UserApi.UserDecoder)

  /** Searches accounts by keyword — `GET /users/search`.
    *
    * '''This endpoint does not return an array.''' It answers `{"ok": true, "data": [...]}`, verified live in
    * `docs/HAZARDS.md` §3 and captured in `golden/user/user-search.json`, and it is decoded through
    * [[com.worxbend.codeberg4s.wire.SearchEnvelopeDto]] for that reason. The `ok` flag is not asserted on: nothing
    * documents what a `false` would mean.
    *
    * Matching is the instance's business — Forgejo matches the keyword against login, full name and email, and the
    * `golden` capture of `q=earl` returns `0x20fearless` and `3pattipearl`, so do not promise a caller prefix matching.
    * A blank keyword is sent verbatim and lists every visible account.
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Api]] with status `422` — or `400`, which Forgejo also uses for input
    * validation, see `docs/HAZARDS.md` §4 — when the instance rejects the query, and `401`/`403` on an instance that
    * requires sign-in to search. [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means the envelope was absent
    * or one of its elements carried no `id` or `login`, and its `path` names the failing element as `$.data[n]`.
    * [[com.worxbend.codeberg4s.CodebergError.Transport]] and [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]]
    * are as for [[current]]. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param keyword
    *   the `q` parameter, passed to the instance unchanged
    * @param params
    *   the page to fetch and how large it may be
    */
  def search(keyword: String, params: PageParams): Future[Page[User]] =
    pipeline.callPage(UserApi.searchRequest(keyword, params), params)(using UserApi.UserSearchDecoder)

  /** Lists the repositories an account owns — `GET /users/{username}/repos`.
    *
    * Only repositories the configured credentials may see are returned, so the same call answers differently for an
    * anonymous client and for the account holder's own token. The elements are
    * [[com.worxbend.codeberg4s.repositories.Repository]] values, the model `client.repos` returns.
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when no such account exists, and `401`/`403` when
    * the instance requires credentials for this listing. [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means
    * an element carried no `id`, `name` or `owner`, reported at `$[n]`.
    * [[com.worxbend.codeberg4s.CodebergError.Transport]] and [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]]
    * are as for [[current]]. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def repositories(username: Username, params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(UserApi.repositoriesRequest(username, params), params)(using UserApi.RepositoryListDecoder)

  /** Lists the accounts following `username` — `GET /users/{username}/followers`.
    *
    * '''Failures.''' As [[repositories]], except that the payload is a list of users, so
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means an element carried no `id` or `login`. Note that
    * `401` is the ordinary anonymous outcome on codeberg.org rather than an exceptional one: the fixture manifest
    * records that this path answers `401 token is required` to an anonymous caller even though the spec marks it
    * public. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def followers(username: Username, params: PageParams): Future[Page[User]] =
    pipeline.callPage(UserApi.followersRequest(username, params), params)(using UserApi.UserListDecoder)

  /** Lists the accounts `username` follows — `GET /users/{username}/following`.
    *
    * '''Failures.''' Identical to [[followers]], including the `401`-when-anonymous caveat. `GET` is safe, so the call
    * is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def following(username: Username, params: PageParams): Future[Page[User]] =
    pipeline.callPage(UserApi.followingRequest(username, params), params)(using UserApi.UserListDecoder)

  /** Lists the public keys of the account the configured credentials belong to — `GET /user/keys`.
    *
    * Public keys are public data — the private half never appears in this API — so nothing in the returned
    * [[PublicKey]] values needs redacting before it is logged or displayed.
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when no credentials were configured or the token
    * was rejected — like [[current]], this path has no anonymous reading — and `403` when the token lacks the scope.
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means an element carried no `id` or `key`, reported at
    * `$[n]`. [[com.worxbend.codeberg4s.CodebergError.Transport]] and
    * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] are as for [[current]]. `GET` is safe, so the call is
    * retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def currentKeys(params: PageParams): Future[Page[PublicKey]] =
    pipeline.callPage(UserApi.currentKeysRequest(params), params)(using UserApi.PublicKeyListDecoder)

  /** Lists the public keys of one account — `GET /users/{username}/keys`.
    *
    * '''Failures.''' As [[currentKeys]], with [[com.worxbend.codeberg4s.CodebergError.Api]] status `404` added for an
    * account that does not exist. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def keys(username: Username, params: PageParams): Future[Page[PublicKey]] =
    pipeline.callPage(UserApi.keysRequest(username, params), params)(using UserApi.PublicKeyListDecoder)

/** The requests this group issues, its typed rail, and the decoders that read its payloads. */
object UserApi:

  /** The stable operation id [[UserApi.current]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]].
    * Safe to alert on.
    */
  val CurrentOperation: String = "users.current"

  /** The stable operation id [[UserApi.get]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]]. */
  val GetOperation: String = "users.get"

  /** The stable operation id [[UserApi.search]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]]. */
  val SearchOperation: String = "users.search"

  /** The stable operation id [[UserApi.repositories]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]].
    */
  val RepositoriesOperation: String = "users.repos"

  /** The operation id [[UserApi.followers]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]]. */
  val FollowersOperation: String = "users.followers"

  /** The operation id [[UserApi.following]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]]. */
  val FollowingOperation: String = "users.following"

  /** The stable operation id [[UserApi.currentKeys]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]].
    */
  val CurrentKeysOperation: String = "users.currentKeys"

  /** The stable operation id [[UserApi.keys]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]]. */
  val KeysOperation: String = "users.keys"

  /** The query parameter Forgejo takes the search keyword from. */
  val KeywordParameter: String = "q"

  /** The typed rail of [[UserApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.users.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: UserApi)(using exec: Exec[Future]):

    /** [[UserApi.current]] with its failure as a value. The returned `Future` never fails with a
      * [[com.worxbend.codeberg4s.CodebergException]].
      */
    def current(): Future[Either[CodebergError, User]] =
      exec.attempt(rail.current())

    /** [[UserApi.get]] with its failure as a value. */
    def get(username: Username): Future[Either[CodebergError, User]] =
      exec.attempt(rail.get(username))

    /** [[UserApi.search]] with its failure as a value. */
    def search(keyword: String, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.search(keyword, params))

    /** [[UserApi.repositories]] with its failure as a value. */
    def repositories(username: Username, params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.repositories(username, params))

    /** [[UserApi.followers]] with its failure as a value. */
    def followers(username: Username, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.followers(username, params))

    /** [[UserApi.following]] with its failure as a value. */
    def following(username: Username, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.following(username, params))

    /** [[UserApi.currentKeys]] with its failure as a value. */
    def currentKeys(params: PageParams): Future[Either[CodebergError, Page[PublicKey]]] =
      exec.attempt(rail.currentKeys(params))

    /** [[UserApi.keys]] with its failure as a value. */
    def keys(username: Username, params: PageParams): Future[Either[CodebergError, Page[PublicKey]]] =
      exec.attempt(rail.keys(username, params))

  private def currentRequest: CodebergRequest =
    read(CurrentOperation, List("user"), Nil)

  private def getRequest(username: Username): CodebergRequest =
    read(GetOperation, List("users", username.value), Nil)

  private def searchRequest(keyword: String, params: PageParams): CodebergRequest =
    read(SearchOperation, List("users", "search"), (KeywordParameter, keyword) :: pageQuery(params))

  private def repositoriesRequest(username: Username, params: PageParams): CodebergRequest =
    read(RepositoriesOperation, List("users", username.value, "repos"), pageQuery(params))

  private def followersRequest(username: Username, params: PageParams): CodebergRequest =
    read(FollowersOperation, List("users", username.value, "followers"), pageQuery(params))

  private def followingRequest(username: Username, params: PageParams): CodebergRequest =
    read(FollowingOperation, List("users", username.value, "following"), pageQuery(params))

  private def currentKeysRequest(params: PageParams): CodebergRequest =
    read(CurrentKeysOperation, List("user", "keys"), pageQuery(params))

  private def keysRequest(username: Username, params: PageParams): CodebergRequest =
    read(KeysOperation, List("users", username.value, "keys"), pageQuery(params))

  /** A `GET` with no body and no extra headers, which is every operation in this group. */
  /** The `page` and `limit` window every listing here sends, rendered by
    * [[com.worxbend.codeberg4s.codec.PagingQuery.window]].
    */
  private def pageQuery(params: PageParams): List[(String, String)] =
    PagingQuery.window(params)

  private val UserDecoder: Decode[User] =
    WireDecode.of(Json.decoder[UserDto])(_.toDomain)

  private val UserListDecoder: Decode[Vector[User]] =
    WireDecode.vector(Json.decoder[Vector[UserDto]])(each(_, _)(_.toDomainAt(_)))

  private val UserSearchDecoder: Decode[Vector[User]] =
    WireDecode.of(Json.decoder[SearchEnvelopeDto[UserDto]]): envelope =>
      each(JsonPath.Root.field("data"), envelope.data)(_.toDomainAt(_))

  private val RepositoryListDecoder: Decode[Vector[Repository]] =
    WireDecode.vector(Json.decoder[Vector[RepositoryDto]])(each(_, _)(_.toDomainAt(_)))

  private val PublicKeyListDecoder: Decode[Vector[PublicKey]] =
    WireDecode.vector(Json.decoder[Vector[PublicKeyDto]])(each(_, _)(_.toDomainAt(_)))

  /** Converts every element of a decoded list, stopping at the first element that will not convert.
    *
    * Each element is converted at its own path below `at`, so a failure says `$[3].login` or `$.data[3].login` rather
    * than "decoding failed" — which is the difference between a usable bug report and a shrug. One bad element fails
    * the whole page, matching what a bare-list body does: a page that silently dropped an item would make a caller's
    * `totalCount` arithmetic lie.
    *
    * @param at
    *   the path of the list itself — `JsonPath.Root` for a bare array body, `Root.field("data")` inside a search
    *   envelope
    */
  private def each[D, A](at: JsonPath, dtos: Vector[D])(
      convert: (D, JsonPath) => Either[DecodeFailure, A]
  ): Either[DecodeFailure, Vector[A]] =
    dtos.indices.foldLeft[Either[DecodeFailure, Vector[A]]](Right(Vector.empty)): (converted, index) =>
      for
        done <- converted
        next <- convert(dtos(index), at.index(index))
      yield done :+ next
