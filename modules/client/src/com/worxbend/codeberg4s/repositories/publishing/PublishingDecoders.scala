package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.repositories.ReleaseAsset
import com.worxbend.codeberg4s.repositories.Tag
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.repositories.wire.ReleaseAssetDto
import com.worxbend.codeberg4s.repositories.wire.TagDto

/** The response shapes [[RepositoryPublishingApi]] can receive that no other group already decodes.
  *
  * Deliberately short. A release and a repository come back from these endpoints in exactly the shape
  * [[com.worxbend.codeberg4s.repositories.RepositoryDecoders]] already reads them in — `POST /releases` answers the
  * same `Release` object as `GET /releases/{id}` — so those decoders are reused rather than restated. Two models of one
  * payload is the defect `docs/LEDGER.md` calls forking, and it is no less a fork for being a decoder.
  *
  * What is genuinely new here is the single `Tag` (the repository group only ever read an array of them) and the
  * `Attachment`, in both its single and its array form: wave 2 read attachments only as a release's nested `assets`,
  * never as a body of their own.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call.
  */
private[publishing] object PublishingDecoders:

  /** One tag object, as `POST /tags` and `GET /tags/{tag}` return it.
    *
    * The same `Tag` model the listing returns, so `golden/repository/tags-list.json` is evidence for this decoder too —
    * the elements of that capture are exactly what these endpoints send one of.
    */
  val tag: Decode[Tag] =
    WireDecode.single(Json.decoder[TagDto])(_.toDomain)

  /** One attachment object, as the three single-asset endpoints return it.
    *
    * `ReleaseAssetDto` exposes only `toDomainAt`, because until now an attachment was always nested inside a release
    * and its failures had to be reported at `$.assets[n]`. Here it '''is''' the body, so the path is the root.
    */
  val asset: Decode[ReleaseAsset] =
    WireDecode.single(Json.decoder[ReleaseAssetDto])(_.toDomainAt(JsonPath.Root))

  /** A bare array of attachment objects, as `GET /releases/{id}/assets` returns it.
    *
    * Each element's failure is reported at its own index, so a listing whose fourth attachment carries no `name` fails
    * at `$[3].name` rather than at `$`.
    */
  val assets: Decode[Vector[ReleaseAsset]] =
    WireDecode.vector(Json.decoder[Vector[ReleaseAssetDto]]): (at, dtos) =>
      Elements.convert(at, dtos)(_.toDomainAt(_))
