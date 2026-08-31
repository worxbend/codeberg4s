package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.core.ResponseBody
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
  * [[createdAccessToken]] does parse JSON, and is listed here only to say where it is: it is the one decoder in this
  * group that yields a usable credential — the others are the Actions runner registration decoders — and it is
  * deliberately not reachable from any listing; see [[com.worxbend.codeberg4s.users.social.wire.AccessTokenDto]].
  */
private[social] object SocialDecoders:

  /** A bare array of user objects, as every follower and following listing returns it. */
  val users: Decode[Vector[User]] =
    WireDecode.vector(Json.decoder[Vector[UserDto]]): (at, dtos) =>
      Elements.convert(at, dtos)(_.toDomainAt(_))

  /** A bare array of repository objects, as the starred and watched listings return them. */
  val repositories: Decode[Vector[Repository]] =
    WireDecode.vector(Json.decoder[Vector[RepositoryDto]]): (at, dtos) =>
      Elements.convert(at, dtos)(_.toDomainAt(_))

  /** A bare array of block entries. */
  val blockedUsers: Decode[Vector[BlockedUser]] =
    WireDecode.vector(Json.decoder[Vector[BlockedUserDto]])(BlockedUserDto.toDomainAll)

  /** A bare array of running stopwatches. */
  val stopWatches: Decode[Vector[StopWatch]] =
    WireDecode.vector(Json.decoder[Vector[StopWatchDto]])(StopWatchDto.toDomainAll)

  /** A bare array of tracked-time entries, read by the model `client.issues` already uses.
    *
    * `/user/times` and `/repos/{owner}/{repo}/issues/{index}/times` return the same `TrackedTime` objects, so the DTO
    * and the domain model are the existing ones rather than a second pair — `docs/LEDGER.md` calls forking a model for
    * a second endpoint the mistake to avoid.
    */
  val trackedTimes: Decode[Vector[TrackedTime]] =
    WireDecode.vector(Json.decoder[Vector[TrackedTimeDto]])(TrackedTimeDto.toDomainAll)

  /** A bare array of activity entries, read by the model `client.repos.admin` already uses.
    *
    * `/users/{username}/activities/feeds` and `/repos/{owner}/{repo}/activities/feeds` return the same `Activity`
    * objects. The domain model is named [[com.worxbend.codeberg4s.repositories.admin.RepositoryActivity]] after the
    * endpoint it was first written for; the name is narrower than the model, and reusing it is still better than a
    * second copy of thirteen fields.
    */
  val activities: Decode[Vector[RepositoryActivity]] =
    WireDecode.vector(Json.decoder[Vector[ActivityDto]])(ActivityDto.toDomainAll)

  /** A bare array of heatmap buckets. Not paged — the endpoint takes no `page` or `limit`. */
  val heatmap: Decode[Vector[HeatmapEntry]] =
    WireDecode.vector(Json.decoder[Vector[HeatmapEntryDto]])(HeatmapEntryDto.toDomainAll)

  /** One GPG key object. */
  val gpgKey: Decode[GpgKey] =
    WireDecode.of(Json.decoder[GpgKeyDto])(_.toDomain)

  /** A bare array of GPG key objects. */
  val gpgKeys: Decode[Vector[GpgKey]] =
    WireDecode.vector(Json.decoder[Vector[GpgKeyDto]])(GpgKeyDto.toDomainAll)

  /** One SSH public key object, as `POST /user/keys` and `GET /user/keys/{id}` return it. */
  val publicKey: Decode[PublicKey] =
    WireDecode.of(Json.decoder[PublicKeyDto])(_.toDomain)

  /** A bare array of access-token objects, with the credential field dropped unconditionally. */
  val accessTokens: Decode[Vector[AccessToken]] =
    WireDecode.vector(Json.decoder[Vector[AccessTokenDto]])(AccessTokenDto.toDomainAll)

  /** The `201` of a token creation — the one decoder in this library that yields a usable personal access token.
    *
    * Marked [[com.worxbend.codeberg4s.core.Decode.sensitive]], because this body '''is''' the credential: a payload
    * that carries `sha1` but fails to decode for some other reason would otherwise put a live token into the excerpt on
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]], which is a value applications log. See
    * [[com.worxbend.codeberg4s.core.ApiPipeline.redactedSnippet]] for what the excerpt becomes instead.
    */
  val createdAccessToken: Decode[CreatedAccessToken] =
    Decode.sensitive(WireDecode.of(Json.decoder[AccessTokenDto])(_.toCreated))

  /** The plain-text challenge `GET /user/gpg_key_token` answers.
    *
    * Read verbatim and then validated, so a body that is blank — which cannot be signed — is a
    * [[com.worxbend.codeberg4s.core.DecodeFailure]] at the document root rather than a token-shaped emptiness handed to
    * a caller. The failure carries [[com.worxbend.codeberg4s.ValidationError.message]] and never the rejected body.
    */
  val verificationToken: Decode[GpgKeyToken] =
    (body: ResponseBody) =>
      PlainText
        .decoder(body)
        .flatMap(text => GpgKeyToken.from(text).left.map(error => DecodeFailure(JsonPath.Root, error.message)))
