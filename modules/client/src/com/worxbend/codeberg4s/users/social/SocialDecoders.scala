package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.TrackedTime
import com.worxbend.codeberg4s.issues.wire.TrackedTimeDto
import com.worxbend.codeberg4s.miscellaneous.PlainText
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.admin.RepositoryActivity
import com.worxbend.codeberg4s.repositories.admin.wire.ActivityDto
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto
import com.worxbend.codeberg4s.users.PublicKey
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.social.wire.AccessTokenDto
import com.worxbend.codeberg4s.users.social.wire.BlockedUserDto
import com.worxbend.codeberg4s.users.social.wire.GpgKeyDto
import com.worxbend.codeberg4s.users.social.wire.HeatmapEntryDto
import com.worxbend.codeberg4s.users.social.wire.StopWatchDto
import com.worxbend.codeberg4s.users.wire.PublicKeyDto
import com.worxbend.codeberg4s.users.wire.UserDto

/** Every response shape [[UserSocialApi]], [[UserKeyApi]] and [[UserTokenApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call, exactly as
  * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionDecoders]] does.
  *
  * ==Every listing here is a bare array==
  *
  * Nothing in this group uses an envelope. `docs/HAZARDS.md` §3 records `/users/search` as the one user-facing endpoint
  * that wraps its results in `{"ok", "data"}`, and none of the thirty-four operations in this group is it — so element
  * failures are reported at `$[n]` throughout, never at `$.data[n]`.
  *
  * ==Two decoders that do not parse JSON, for opposite reasons==
  *
  * [[verificationToken]] reads `text/plain`: the spec declares `produces: text/plain` and the `APIString` response for
  * `GET /user/gpg_key_token`, so the body '''is''' the token. Running it through a JSON parser would turn a good
  * response into a decoding failure.
  *
  * [[createdAccessToken]] does parse JSON, and is listed here only to say where it is: it is the one decoder in the
  * library that yields a usable credential, and it is deliberately not reachable from any listing — see
  * [[com.worxbend.codeberg4s.users.social.wire.AccessTokenDto]].
  */
private[social] object SocialDecoders:

  /** A bare array of user objects, as every follower and following listing returns it. */
  val users: Decode[Vector[User]] =
    WireDecode.of(Json.decoder[Vector[UserDto]]): dtos =>
      Elements.convert(JsonPath.Root, dtos)((dto, path) => dto.toDomainAt(path))

  /** A bare array of repository objects, as the starred and watched listings return them. */
  val repositories: Decode[Vector[Repository]] =
    WireDecode.of(Json.decoder[Vector[RepositoryDto]]): dtos =>
      Elements.convert(JsonPath.Root, dtos)((dto, path) => dto.toDomainAt(path))

  /** A bare array of block entries. */
  val blockedUsers: Decode[Vector[BlockedUser]] =
    WireDecode.of(Json.decoder[Vector[BlockedUserDto]])(dtos => BlockedUserDto.toDomainAll(JsonPath.Root, dtos))

  /** A bare array of running stopwatches. */
  val stopWatches: Decode[Vector[StopWatch]] =
    WireDecode.of(Json.decoder[Vector[StopWatchDto]])(dtos => StopWatchDto.toDomainAll(JsonPath.Root, dtos))

  /** A bare array of tracked-time entries, read by the model `client.issues` already uses.
    *
    * `/user/times` and `/repos/{owner}/{repo}/issues/{index}/times` return the same `TrackedTime` objects, so the DTO
    * and the domain model are the existing ones rather than a second pair — `docs/LEDGER.md` calls forking a model for
    * a second endpoint the mistake to avoid.
    */
  val trackedTimes: Decode[Vector[TrackedTime]] =
    WireDecode.of(Json.decoder[Vector[TrackedTimeDto]])(dtos => TrackedTimeDto.toDomainAll(JsonPath.Root, dtos))

  /** A bare array of activity entries, read by the model `client.repos.admin` already uses.
    *
    * `/users/{username}/activities/feeds` and `/repos/{owner}/{repo}/activities/feeds` return the same `Activity`
    * objects. The domain model is named [[com.worxbend.codeberg4s.repositories.admin.RepositoryActivity]] after the
    * endpoint it was first written for; the name is narrower than the model, and reusing it is still better than a
    * second copy of thirteen fields.
    */
  val activities: Decode[Vector[RepositoryActivity]] =
    WireDecode.of(Json.decoder[Vector[ActivityDto]])(dtos => ActivityDto.toDomainAll(JsonPath.Root, dtos))

  /** A bare array of heatmap buckets. Not paged — the endpoint takes no `page` or `limit`. */
  val heatmap: Decode[Vector[HeatmapEntry]] =
    WireDecode.of(Json.decoder[Vector[HeatmapEntryDto]])(dtos => HeatmapEntryDto.toDomainAll(JsonPath.Root, dtos))

  /** One GPG key object. */
  val gpgKey: Decode[GpgKey] =
    WireDecode.of(Json.decoder[GpgKeyDto])(_.toDomain)

  /** A bare array of GPG key objects. */
  val gpgKeys: Decode[Vector[GpgKey]] =
    WireDecode.of(Json.decoder[Vector[GpgKeyDto]])(dtos => GpgKeyDto.toDomainAll(JsonPath.Root, dtos))

  /** One SSH public key object, as `POST /user/keys` and `GET /user/keys/{id}` return it. */
  val publicKey: Decode[PublicKey] =
    WireDecode.of(Json.decoder[PublicKeyDto])(_.toDomain)

  /** A bare array of access-token objects, with the credential field dropped unconditionally. */
  val accessTokens: Decode[Vector[AccessToken]] =
    WireDecode.of(Json.decoder[Vector[AccessTokenDto]])(dtos => AccessTokenDto.toDomainAll(JsonPath.Root, dtos))

  /** The `201` of a token creation — the one decoder in this library that yields a usable credential. */
  val createdAccessToken: Decode[CreatedAccessToken] =
    WireDecode.of(Json.decoder[AccessTokenDto])(_.toCreated)

  /** The plain-text challenge `GET /user/gpg_key_token` answers.
    *
    * Read verbatim and then validated, so a body that is blank — which cannot be signed — is a
    * [[com.worxbend.codeberg4s.core.DecodeFailure]] at the document root rather than a token-shaped emptiness handed to
    * a caller. The failure carries [[com.worxbend.codeberg4s.ValidationError.message]] and never the rejected body.
    */
  val verificationToken: Decode[GpgKeyToken] =
    (body: String) =>
      PlainText
        .decoder(body)
        .flatMap(text => GpgKeyToken.from(text).left.map(error => DecodeFailure(JsonPath.Root, error.message)))
