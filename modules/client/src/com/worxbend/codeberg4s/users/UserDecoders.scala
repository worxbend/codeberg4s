package com.worxbend.codeberg4s.users

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto
import com.worxbend.codeberg4s.users.wire.{PublicKeyDto, UserDto}
import com.worxbend.codeberg4s.wire.SearchEnvelopeDto

/** Every response shape [[UserApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call: a decoder is a function
  * from a body to a value, and allocating a new one per request would be waste with no upside.
  *
  * Two envelope shapes appear in this one group, which is why they are collected here rather than inlined: most
  * listings are a bare JSON array, but `GET /users/search` wraps its results in `{"ok", "data"}` and so reports
  * element failures at `$.data[n]`. `docs/HAZARDS.md` §3 records that exception.
  */
private[users] object UserDecoders:

  /** One user object, as `GET /user` and `GET /users/{username}` return it. */
  val user: Decode[User] =
    WireDecode.single(Json.decoder[UserDto])(_.toDomain)

  /** A bare array of user objects, as the follower and following listings return them. */
  val users: Decode[Vector[User]] =
    WireDecode.vector(Json.decoder[Vector[UserDto]])

  /** The `{"ok", "data"}` envelope the user search returns. */
  val searchResults: Decode[Vector[User]] =
    WireDecode.single(Json.decoder[SearchEnvelopeDto[UserDto]]): envelope =>
      WireModel.all(JsonPath.Root.field("data"), envelope.data)

  /** A bare array of repository objects, read by the model `client.repos` already uses. */
  val repositories: Decode[Vector[Repository]] =
    WireDecode.vector(Json.decoder[Vector[RepositoryDto]])

  /** A bare array of SSH public keys. */
  val publicKeys: Decode[Vector[PublicKey]] =
    WireDecode.vector(Json.decoder[Vector[PublicKeyDto]])
