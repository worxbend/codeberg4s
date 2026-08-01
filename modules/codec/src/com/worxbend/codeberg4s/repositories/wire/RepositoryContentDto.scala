package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.RepositoryContent

/** The two shapes `GET /repos/{owner}/{repo}/contents/{filepath}` answers with, plus the one it must not.
  *
  * '''Why this is an enum and not a case class.''' The response is an untagged union discriminated only by the JSON
  * kind of the top-level value: an object for a single entry, an array for a directory. `docs/HAZARDS.md` §3 measured
  * both against the live instance and showed the pinned spec declaring only the object arm, which is why every
  * generated client gets this endpoint wrong. There is no discriminator field to read — the decision is made by looking
  * at what kind of JSON value arrived, and by nothing else.
  *
  * [[Unexpected]] exists because an upickle `Reader` cannot report a failure: it either produces a value or aborts, and
  * this module promises never to let a codec exception escape. So a third shape decodes into a value that names what
  * arrived, and [[toDomain]] turns it into a [[com.worxbend.codeberg4s.core.DecodeFailure]] — the same failure any
  * other unusable payload produces, at the same place in the pipeline.
  */
enum RepositoryContentDto:

  /** The body was a JSON object: the path addressed one entry. */
  case Single(entry: ContentEntryDto)

  /** The body was a JSON array: the path addressed a directory. */
  case Listing(entries: Vector[ContentEntryDto])

  /** The body was neither, or was an array holding something that is not an entry.
    *
    * @param shape
    *   a short description of what arrived, for the failure message. Never the payload itself — bounding a body excerpt
    *   is [[com.worxbend.codeberg4s.core.ApiPipeline]]'s job, and it does it once for every endpoint
    */
  case Unexpected(shape: String)

  /** Converts to the domain.
    *
    * Fails when the body was neither a JSON object nor a JSON array of objects, and when any entry could not be
    * converted — the latter at the entry's own path, index included for a directory.
    */
  def toDomain: Either[DecodeFailure, RepositoryContent] =
    this match
      case Single(entry)     =>
        entry.toDomainAt(JsonPath.Root).map(RepositoryContent.File.apply)
      case Listing(entries)  =>
        Elements
          .convert(JsonPath.Root, entries)((dto, path) => dto.toDomainAt(path))
          .map(RepositoryContent.Directory.apply)
      case Unexpected(shape) =>
        Left(DecodeFailure(JsonPath.Root, s"expected a contents object or an array of them, got $shape"))

object RepositoryContentDto:

  /** Reads whichever arm arrived.
    *
    * Decodes to `ujson.Value` first and branches on its kind, because that is the only discriminator the endpoint
    * offers. The raw value goes no further than this object: what leaves is a typed DTO, so no `ujson.Value` reaches
    * the domain or a caller.
    */
  given upickle.default.Reader[RepositoryContentDto] =
    upickle.default.reader[ujson.Value].map(fromJson)

  /** Branches on the JSON kind of an already-parsed body. Total by construction — every shape maps to a case. */
  def fromJson(value: ujson.Value): RepositoryContentDto =
    value.objOpt match
      case Some(entry) => Single(entryOf(entry))
      case None        =>
        value.arrOpt match
          case Some(elements) => listing(elements.toVector)
          case None           => Unexpected(describe(value))

  /** An array is a directory only if every element is an object; anything else is a shape this endpoint does not have. */
  private def listing(elements: Vector[ujson.Value]): RepositoryContentDto =
    if elements.forall(_.objOpt.isDefined) then Listing(elements.flatMap(_.objOpt).map(entryOf))
    else Unexpected("an array holding a value that is not an object")

  private def entryOf(entry: scala.collection.Map[String, ujson.Value]): ContentEntryDto =
    ContentEntryDto.fromFields(JsonFields(entry.toMap))

  private def describe(value: ujson.Value): String =
    if value.isNull then "null"
    else if value.strOpt.isDefined then "a string"
    else if value.numOpt.isDefined then "a number"
    else if value.boolOpt.isDefined then "a boolean"
    else "a value of an unrecognised kind"
