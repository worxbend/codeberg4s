package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.users.social.wire.{CreateGpgKeyOptionDto, CreateKeyOptionDto, VerifyGpgKeyOptionDto}
import com.worxbend.codeberg4s.users.{PublicKey, Username}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod}

import scala.concurrent.Future

/** The keys an account signs and authenticates with: SSH keys, GPG keys, and the handshake that proves a GPG key
  * belongs to the account holder.
  *
  * Reached as `client.users.keys`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[UserKeyApi.attempt]] never fail and
  * return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Nothing here is a secret==
  *
  * Every key this class reads or writes is a '''public''' key. The private halves never leave the account holder's
  * machine and never appear in this API, so nothing returned here needs redacting before it is logged or displayed —
  * see [[com.worxbend.codeberg4s.users.PublicKey]] and [[GpgKey]]. The only value in this group that carries a
  * redaction discipline is the one that does not belong to it: an access token, which lives on [[UserTokenApi]].
  *
  * [[GpgKeyToken]] is not a credential either, despite the name; see that type.
  *
  * ==The two listings live on `client.users`==
  *
  * `GET /user/keys` and `GET /users/{username}/keys` were implemented before this group existed and remain on
  * `client.users` as `currentKeys` and `keys`. Duplicating them here would give a caller two spellings of one request
  * and two places for the paging behaviour to drift. What is here is everything else: creating, reading and deleting a
  * single SSH key, and the whole GPG surface.
  *
  * ==Evidence==
  *
  * '''Every model in this group is derived from `spec/swagger.v1.json`, not from a captured response.''' The harvest
  * behind `modules/codec/test/resources/golden` was anonymous, `/user/keys` and `/user/gpg_keys` answer `401` without a
  * token, and `/users/{username}/gpg_keys` was not among the 61 captured paths. The field sets are the spec read
  * literally and the nullability treatment is the conservative one `docs/HAZARDS.md` §1 mandates. Where a shape is
  * asserted in a test, the payload was written by hand to match that definition — it is not evidence that Forgejo sends
  * exactly this.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method:
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when no credentials were configured or the token
  *     was rejected, `403` when the token lacks the scope, `404` when the key or account does not exist, and `422` — or
  *     `400`, which `docs/HAZARDS.md` §4 records Forgejo using interchangeably — when the instance rejected the key
  *     material, the signature, or a key it already holds.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every `POST` uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]: registering a key creates a resource with a fresh
  * identifier, Forgejo offers no idempotency key, and a repeat after a lost success either registers a second entry or
  * earns a `422` for a duplicate — neither of which a caller asked for. Both `DELETE`s use
  * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], and the justification is per method.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class UserKeyApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: UserKeyApi.Attempt = UserKeyApi.Attempt(this)

  // --- SSH keys -------------------------------------------------------------

  /** Registers an SSH key — `POST /user/keys`.
    *
    * The key may push as well as fetch unless [[CreateSshKey.readOnly]] said otherwise; see that type for why the flag
    * is always sent.
    *
    * '''Never retried''', under [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]. A repeat after a lost success
    * cannot register the same key twice — Forgejo rejects a duplicate — but it answers `422`, which a caller would read
    * as "my key was refused" when in fact it was accepted. Reporting the transport failure honestly is better than
    * converting it into a misleading validation failure.
    *
    * '''Answers `201`''' with the registered key, including the identifier [[deleteKey]] takes.
    *
    * '''Failures.''' The group contract above; a decoding failure means the payload carried no `id` or `key`.
    */
  def createKey(command: CreateSshKey): Future[PublicKey] =
    pipeline.call(UserKeyApi.createKeyRequest(command), RetryEligibility.Never)(using SocialDecoders.publicKey)

  /** Reads one registered SSH key — `GET /user/keys/{id}`.
    *
    * '''Failures.''' The group contract above; `404` means the account has no key with that identifier. `GET` is safe,
    * so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def key(id: SshKeyId): Future[PublicKey] =
    pipeline.call(UserKeyApi.keyRequest(id), RetryEligibility.IdempotentOnly)(using SocialDecoders.publicKey)

  /** Removes an SSH key — `DELETE /user/keys/{id}`.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], because the request names one
    * instance-wide identifier the server never reuses: the same `id` addresses the same key or nothing at all, so
    * repeating the call converges on "the key is gone" and can never remove a key the caller did not name. The cost is
    * that a retry after a lost success answers `404`, so a `404` here means "it is gone" rather than necessarily "it
    * was never there".
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteKey(id: SshKeyId): Future[Unit] =
    pipeline.callUnit(UserKeyApi.deleteKeyRequest(id), RetryEligibility.AlwaysRetry)

  // --- GPG keys -------------------------------------------------------------

  /** Lists the GPG keys of the credentials' own account — `GET /user/gpg_keys`.
    *
    * '''Failures.''' The group contract above; a decoding failure means an element carried no `id`, reported at `$[n]`.
    * `GET` is safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def gpgKeys(params: PageParams): Future[Page[GpgKey]] =
    pipeline.callPage(UserKeyApi.gpgKeysRequest(params), params)(using SocialDecoders.gpgKeys)

  /** Lists the GPG keys of one account — `GET /users/{username}/gpg_keys`.
    *
    * '''Failures.''' As [[gpgKeys]], with `404` added for an account that does not exist. `GET` is safe, so the call is
    * retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def gpgKeysOf(username: Username, params: PageParams): Future[Page[GpgKey]] =
    pipeline.callPage(UserKeyApi.gpgKeysOfRequest(username, params), params)(using SocialDecoders.gpgKeys)

  /** Reads one GPG key — `GET /user/gpg_keys/{id}`.
    *
    * The `{id}` is the instance-local row number, not the OpenPGP key id; see [[GpgKeyId]] for why those are different
    * types.
    *
    * '''Failures.''' The group contract above. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def gpgKey(id: GpgKeyId): Future[GpgKey] =
    pipeline.call(UserKeyApi.gpgKeyRequest(id), RetryEligibility.IdempotentOnly)(using SocialDecoders.gpgKey)

  /** Registers a GPG key — `POST /user/gpg_keys`.
    *
    * A key registered without a signature arrives with [[GpgKey.isVerified]] `false`, and commits signed with it are
    * not attributed until the handshake in [[verificationToken]] and [[verifyGpgKey]] completes.
    * [[CreateGpgKey.provingPossession]] does both in one call.
    *
    * '''Never retried''', for the reason [[createKey]] gives.
    *
    * '''Answers `201`''' with the registered key.
    *
    * '''Failures.''' The group contract above; `422` covers a key block the instance will not parse, a key it already
    * holds, and a signature that does not verify.
    */
  def createGpgKey(command: CreateGpgKey): Future[GpgKey] =
    pipeline.call(UserKeyApi.createGpgKeyRequest(command), RetryEligibility.Never)(using SocialDecoders.gpgKey)

  /** Removes a GPG key — `DELETE /user/gpg_keys/{id}`.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], by exactly the argument
    * [[deleteKey]] makes: the identifier is instance-wide and never reused.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteGpgKey(id: GpgKeyId): Future[Unit] =
    pipeline.callUnit(UserKeyApi.deleteGpgKeyRequest(id), RetryEligibility.AlwaysRetry)

  // --- the verification handshake -------------------------------------------

  /** Obtains the challenge to sign — `GET /user/gpg_key_token`.
    *
    * Step one of two. The returned [[GpgKeyToken]] is signed out of band with the private half of the key being proved,
    * and the signature is returned by [[verifyGpgKey]]. See [[GpgKeyToken]] for the whole handshake and for why the
    * token is '''not''' a credential.
    *
    * '''This endpoint answers `text/plain`.''' The spec declares `produces: text/plain` and the `APIString` response,
    * so the body is the token itself rather than a JSON document, and it is read by
    * [[com.worxbend.codeberg4s.miscellaneous.PlainText]] and never by a JSON parser.
    *
    * '''Failures.''' The group contract above, except that a decoding failure here means only one thing: the body was
    * blank, which cannot be signed. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]] — and a retry is harmless because the instance
    * derives the token from the account rather than storing one per request.
    */
  def verificationToken(): Future[GpgKeyToken] =
    pipeline.call(UserKeyApi.verificationTokenRequest, RetryEligibility.IdempotentOnly)(using
      SocialDecoders.verificationToken)

  /** Proves possession of a registered GPG key — `POST /user/gpg_key_verify`.
    *
    * Step two of two, and unreachable without step one: a [[VerifyGpgKey]] can only be built by
    * [[GpgKeyToken.signedWith]], so a caller who has not fetched a challenge has nothing to pass here.
    *
    * '''Never retried''', under [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]. Verification is arguably
    * idempotent — verifying a verified key changes nothing — but it is a `POST`, this library repeats no `POST`, and
    * the tokens Forgejo issues are time-bounded, so a repeat late enough to matter is a repeat likely to fail for a
    * different reason than the first attempt did.
    *
    * '''Answers `201`''' with the key, now carrying [[GpgKey.isVerified]] `true`.
    *
    * '''Failures.''' The group contract above; `422` means the signature did not verify against the token, and `404`
    * means the named [[OpenPgpKeyId]] is not one of the account's registered keys.
    */
  def verifyGpgKey(command: VerifyGpgKey): Future[GpgKey] =
    pipeline.call(UserKeyApi.verifyGpgKeyRequest(command), RetryEligibility.Never)(using SocialDecoders.gpgKey)

/** The requests this group issues and its typed rail. */
object UserKeyApi:

  /** The stable operation id [[UserKeyApi.createKey]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val CreateKeyOperation: String = "users.keys.create"

  /** The stable operation id of [[UserKeyApi.key]]. */
  val KeyOperation: String = "users.keys.read"

  /** The stable operation id of [[UserKeyApi.deleteKey]]. */
  val DeleteKeyOperation: String = "users.keys.delete"

  /** The stable operation id of [[UserKeyApi.gpgKeys]]. */
  val GpgKeysOperation: String = "users.keys.gpg.list"

  /** The stable operation id of [[UserKeyApi.gpgKeysOf]]. */
  val GpgKeysOfOperation: String = "users.keys.gpg.listFor"

  /** The stable operation id of [[UserKeyApi.gpgKey]]. */
  val GpgKeyOperation: String = "users.keys.gpg.read"

  /** The stable operation id of [[UserKeyApi.createGpgKey]]. */
  val CreateGpgKeyOperation: String = "users.keys.gpg.create"

  /** The stable operation id of [[UserKeyApi.deleteGpgKey]]. */
  val DeleteGpgKeyOperation: String = "users.keys.gpg.delete"

  /** The stable operation id of [[UserKeyApi.verificationToken]]. */
  val VerificationTokenOperation: String = "users.keys.gpg.verificationToken"

  /** The stable operation id of [[UserKeyApi.verifyGpgKey]]. */
  val VerifyGpgKeyOperation: String = "users.keys.gpg.verify"

  /** The typed rail of [[UserKeyApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.users.keys.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: UserKeyApi)(using exec: Exec[Future]):

    /** [[UserKeyApi.createKey]] with its failure as a value. The returned `Future` never fails with a
      * [[com.worxbend.codeberg4s.CodebergException]].
      */
    def createKey(command: CreateSshKey): Future[Either[CodebergError, PublicKey]] =
      exec.attempt(rail.createKey(command))

    /** [[UserKeyApi.key]] with its failure as a value. */
    def key(id: SshKeyId): Future[Either[CodebergError, PublicKey]] =
      exec.attempt(rail.key(id))

    /** [[UserKeyApi.deleteKey]] with its failure as a value. */
    def deleteKey(id: SshKeyId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteKey(id))

    /** [[UserKeyApi.gpgKeys]] with its failure as a value. */
    def gpgKeys(params: PageParams): Future[Either[CodebergError, Page[GpgKey]]] =
      exec.attempt(rail.gpgKeys(params))

    /** [[UserKeyApi.gpgKeysOf]] with its failure as a value. */
    def gpgKeysOf(username: Username, params: PageParams): Future[Either[CodebergError, Page[GpgKey]]] =
      exec.attempt(rail.gpgKeysOf(username, params))

    /** [[UserKeyApi.gpgKey]] with its failure as a value. */
    def gpgKey(id: GpgKeyId): Future[Either[CodebergError, GpgKey]] =
      exec.attempt(rail.gpgKey(id))

    /** [[UserKeyApi.createGpgKey]] with its failure as a value. */
    def createGpgKey(command: CreateGpgKey): Future[Either[CodebergError, GpgKey]] =
      exec.attempt(rail.createGpgKey(command))

    /** [[UserKeyApi.deleteGpgKey]] with its failure as a value. */
    def deleteGpgKey(id: GpgKeyId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteGpgKey(id))

    /** [[UserKeyApi.verificationToken]] with its failure as a value. */
    def verificationToken(): Future[Either[CodebergError, GpgKeyToken]] =
      exec.attempt(rail.verificationToken())

    /** [[UserKeyApi.verifyGpgKey]] with its failure as a value. */
    def verifyGpgKey(command: VerifyGpgKey): Future[Either[CodebergError, GpgKey]] =
      exec.attempt(rail.verifyGpgKey(command))

  private def createKeyRequest(command: CreateSshKey): CodebergRequest =
    write(CreateKeyOperation, HttpMethod.Post, List("user", "keys"), CreateKeyOptionDto.render(command))

  private def keyRequest(id: SshKeyId): CodebergRequest =
    read(KeyOperation, keyPath(id), Nil)

  private def deleteKeyRequest(id: SshKeyId): CodebergRequest =
    remove(DeleteKeyOperation, keyPath(id))

  private def gpgKeysRequest(params: PageParams): CodebergRequest =
    read(GpgKeysOperation, List("user", "gpg_keys"), PagingQuery.window(params))

  private def gpgKeysOfRequest(username: Username, params: PageParams): CodebergRequest =
    read(GpgKeysOfOperation, List("users", username.value, "gpg_keys"), PagingQuery.window(params))

  private def gpgKeyRequest(id: GpgKeyId): CodebergRequest =
    read(GpgKeyOperation, gpgKeyPath(id), Nil)

  private def createGpgKeyRequest(command: CreateGpgKey): CodebergRequest =
    write(CreateGpgKeyOperation, HttpMethod.Post, List("user", "gpg_keys"), CreateGpgKeyOptionDto.render(command))

  private def deleteGpgKeyRequest(id: GpgKeyId): CodebergRequest =
    remove(DeleteGpgKeyOperation, gpgKeyPath(id))

  private def verificationTokenRequest: CodebergRequest =
    read(VerificationTokenOperation, List("user", "gpg_key_token"), Nil)

  private def verifyGpgKeyRequest(command: VerifyGpgKey): CodebergRequest =
    write(
      VerifyGpgKeyOperation,
      HttpMethod.Post,
      List("user", "gpg_key_verify"),
      VerifyGpgKeyOptionDto.render(command),
    )

  private def keyPath(id: SshKeyId): List[String] =
    List("user", "keys", id.value.toString)

  private def gpgKeyPath(id: GpgKeyId): List[String] =
    List("user", "gpg_keys", id.value.toString)
