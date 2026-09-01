package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure

/** The one thing every wire DTO can do: turn itself into the domain model it mirrors.
  *
  * A DTO says what its fields are; this says what it is '''for'''. Each one already had the same two members written
  * out by hand — a `toDomainAt` carrying the path of the enclosing model, and a `toDomain` that was
  * `toDomainAt(JsonPath.Root)` in every single case — so the second one lives here instead of being copied per model.
  *
  * Extending this does not stop a DTO being a plain `final case class`: it adds no field, no state and no equality of
  * its own. What it does add is a name for the capability, which is what lets a helper such as [[WireModel.all]] or
  * `client.WireDecode.vector` be written once for every model rather than once per model.
  *
  * @tparam A
  *   the domain model this DTO converts into
  */
trait WireModel[A]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `at` is the path of '''this''' model inside the response document — [[com.worxbend.codeberg4s.JsonPath.Root]] for
    * a top-level object, `Root.index(2).field("owner")` for the owner of the third element of a list — so that a
    * missing field is reported where a reader of the payload would look for it.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, A]

  /** [[toDomainAt]] for a payload that is the whole response body. */
  final def toDomain: Either[DecodeFailure, A] =
    toDomainAt(JsonPath.Root)

/** The bulk conversion every list-shaped payload needs. */
object WireModel:

  /** Converts a decoded array of DTOs, reporting the position of whichever element failed.
    *
    * Every model used to carry a `toDomainAll` companion method whose body was this exact expression. Since
    * [[WireModel.toDomainAt]] names the capability the fold needs, the fold no longer has to be written per model.
    *
    * @param at
    *   the path of the '''array''' itself — [[com.worxbend.codeberg4s.JsonPath.Root]] for a response body that is an
    *   array, the field's path for an array nested in an object
    * @param dtos
    *   the decoded elements, in the order the server sent them
    * @return
    *   the converted models, or the first element failure, positioned as `$[2].name`
    */
  def all[D <: WireModel[A], A](at: JsonPath, dtos: Vector[D]): Either[DecodeFailure, Vector[A]] =
    ArrayElements.convert(at, dtos)((dto, path) => dto.toDomainAt(path))
