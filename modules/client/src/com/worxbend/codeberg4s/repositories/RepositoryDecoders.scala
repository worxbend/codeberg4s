package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.{ArrayElements, Json}
import com.worxbend.codeberg4s.core.{Decode, DecodeFailure}
import com.worxbend.codeberg4s.repositories.wire.{
  BranchDto,
  CommitDto,
  ReleaseDto,
  RepositoryContentDto,
  RepositoryDto,
  TagDto,
  TopicNamesDto
}
import com.worxbend.codeberg4s.wire.SearchEnvelopeDto

/** Every response shape [[RepositoryApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call: a decoder is a function
  * from a body to a value, and allocating a new one per request would be waste with no upside.
  *
  * Three envelope shapes appear in this one endpoint group, which is why they are collected here rather than inlined:
  * most listings are a bare JSON array, `GET /repos/search` wraps its results in `{"ok", "data"}`, and
  * `GET /repos/{owner}/{repo}/topics` wraps its names in `{"topics"}`. `docs/HAZARDS.md` §3 records the first two;
  * `golden/repository/topics.json` is the evidence for the third.
  */
private[repositories] object RepositoryDecoders:

  /** A repository object, as `GET /repos/{owner}/{repo}` returns it. */
  val repository: Decode[Repository] =
    WireDecode.single(Json.decoder[RepositoryDto])(_.toDomain)

  /** A bare array of repository objects, as the fork listing returns it. */
  val repositories: Decode[Vector[Repository]] =
    listOf(Json.decoder[Vector[RepositoryDto]])((dto, at) => dto.toDomainAt(at))

  /** The `{"ok", "data"}` envelope the search endpoint returns. */
  val searchResults: Decode[Vector[Repository]] =
    WireDecode.single(Json.decoder[SearchEnvelopeDto[RepositoryDto]]): envelope =>
      ArrayElements.convert(JsonPath.Root.field("data"), envelope.data)((dto, at) => dto.toDomainAt(at))

  /** One branch object. */
  val branch: Decode[Branch] =
    WireDecode.single(Json.decoder[BranchDto])(_.toDomain)

  /** A bare array of branch objects. */
  val branches: Decode[Vector[Branch]] =
    listOf(Json.decoder[Vector[BranchDto]])((dto, at) => dto.toDomainAt(at))

  /** A bare array of tag objects. */
  val tags: Decode[Vector[Tag]] =
    listOf(Json.decoder[Vector[TagDto]])((dto, at) => dto.toDomainAt(at))

  /** A bare array of commit objects. */
  val commits: Decode[Vector[Commit]] =
    listOf(Json.decoder[Vector[CommitDto]])((dto, at) => dto.toDomainAt(at))

  /** One release object. */
  val release: Decode[Release] =
    WireDecode.single(Json.decoder[ReleaseDto])(_.toDomain)

  /** A bare array of release objects. */
  val releases: Decode[Vector[Release]] =
    listOf(Json.decoder[Vector[ReleaseDto]])((dto, at) => dto.toDomainAt(at))

  /** The `{"topics"}` envelope, unwrapped to the names it carries. */
  val topics: Decode[Vector[String]] =
    WireDecode.single(Json.decoder[TopicNamesDto])(dto => Right(dto.toDomain))

  /** Either arm of the contents union — see [[com.worxbend.codeberg4s.repositories.RepositoryContent]]. */
  val contents: Decode[RepositoryContent] =
    WireDecode.single(Json.decoder[RepositoryContentDto])(_.toDomain)

  /** A response body that is an array of DTOs, converted element by element with each failure at its own index. */
  private def listOf[D, A](wire: Decode[Vector[D]])(
      one: (D, JsonPath) => Either[DecodeFailure, A]
  ): Decode[Vector[A]] =
    WireDecode.single(wire)(dtos => ArrayElements.convert(JsonPath.Root, dtos)(one))
