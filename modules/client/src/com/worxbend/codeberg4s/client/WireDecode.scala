package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.core.ResponseBody

/** Joins the two halves of reading a response: parse the wire DTO, then project it into the domain.
  *
  * `modules/codec` deliberately splits those steps — `Json.decoder[D]` knows the JSON shape and `D.toDomain` knows what
  * the domain cannot do without — and every endpoint in this module needs them composed the same way. Doing that once
  * here keeps the composition, and therefore the failure contract, identical for every operation: a failure from either
  * half arrives as the same [[com.worxbend.codeberg4s.core.DecodeFailure]], which
  * [[com.worxbend.codeberg4s.core.ApiPipeline]] lifts into [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]]
  * with a bounded body snippet.
  *
  * Internal: this is the seam every future endpoint wave uses, not something a caller of the library ever names.
  */
private[codeberg4s] object WireDecode:

  /** A decoder that reads `D` from the body and converts it, stopping at the first failure.
    *
    * @param wire
    *   the codec module's reader for the wire DTO
    * @param toDomain
    *   the DTO's own projection, which reports the JSON path of whatever the domain required and did not get
    */
  def of[D, A](wire: Decode[D])(toDomain: D => Either[DecodeFailure, A]): Decode[A] =
    (body: ResponseBody) => wire(body).flatMap(toDomain)

  /** A decoder for a response whose whole body is a JSON array, converted element by element.
    *
    * This is [[of]] with the one detail every list endpoint would otherwise repeat filled in: the array is the whole
    * body, so the base path handed to the DTO's `toDomainAll` is [[com.worxbend.codeberg4s.JsonPath.Root]] and the
    * element paths it reports read as `[0].name` rather than something rooted at a field that does not exist. Writing
    * that once means a list endpoint names its DTO and its projection and nothing else.
    *
    * Use [[of]] instead when the array is nested inside an envelope object, because then the base path is that
    * envelope's field, not the root.
    *
    * @param wire
    *   the codec module's reader for the array of wire DTOs
    * @param toDomainAll
    *   the DTO companion's bulk projection, which takes the base path of the array it is converting
    */
  def vector[D, A](
      wire: Decode[Vector[D]]
  )(toDomainAll: (JsonPath, Vector[D]) => Either[DecodeFailure, Vector[A]]): Decode[Vector[A]] =
    of(wire)(dtos => toDomainAll(JsonPath.Root, dtos))
