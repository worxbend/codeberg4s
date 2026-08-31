package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.{ArrayElements, Json}
import com.worxbend.codeberg4s.core.{Decode, DecodeFailure}
import com.worxbend.codeberg4s.issues.wire.{IssueDto, TrackedTimeDto}
import com.worxbend.codeberg4s.issues.{Issue, TrackedTime}
import com.worxbend.codeberg4s.miscellaneous.{PlainText, SigningKey}
import com.worxbend.codeberg4s.repositories.admin.wire.{
  ActivityDto,
  FilesResponseDto,
  IssuePinsAllowedDto,
  LanguageStatisticsDto,
  PushMirrorDto,
  SyncForkInfoDto,
  TopicSearchEnvelopeDto,
  TopicSummaryDto,
  WatchInfoDto
}
import com.worxbend.codeberg4s.repositories.gitdata.FileChange
import com.worxbend.codeberg4s.repositories.gitdata.wire.FileResponseDto
import com.worxbend.codeberg4s.repositories.wire.{BranchDto, ContentEntryDto, RepositoryDto}
import com.worxbend.codeberg4s.repositories.{Branch, ContentEntry, Repository}
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** Every response shape [[RepositoryAdminApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call, exactly as
  * [[com.worxbend.codeberg4s.repositories.RepositoryDecoders]] does.
  *
  * ==Three envelope shapes, and two responses that are not JSON at all==
  *
  * Most listings here are the bare JSON array the rest of the API returns. `GET /topics/search` is not: it wraps its
  * results in `{"topics": [...]}`, which is why its elements are reported at `$.topics[n]` and not at `$[n]` — see
  * [[com.worxbend.codeberg4s.repositories.admin.wire.TopicSearchEnvelopeDto]]. `GET /repos/{owner}/{repo}/languages` is
  * a bare object with no fixed keys, which no field-by-field DTO can express, so it has one of its own. And
  * `GET /repos/{owner}/{repo}/signing-key.gpg` answers `text/plain`, so it is read by
  * [[com.worxbend.codeberg4s.miscellaneous.PlainText]] and never by a JSON parser.
  *
  * ==Two models come from other groups on purpose==
  *
  * A tracked-time entry is the same `TrackedTime` the per-issue listings return, and a single-file write answers the
  * same `FileResponse` the diff-patch endpoint does. Both reuse the DTO that already exists rather than declaring a
  * second reading of the same wire model, which is what would let the two drift.
  */
private[admin] object RepositoryAdminDecoders:

  /** Where the elements of a `{"topics": [...]}` envelope sit, so a bad element reports `$.topics[2].id`. */
  private val TopicEntriesPath: JsonPath =
    JsonPath.Root.field(TopicSearchEnvelopeDto.EntriesKey)

  /** A repository object, as create, edit, migrate, transfer and convert all answer. */
  val repository: Decode[Repository] =
    WireDecode.single(Json.decoder[RepositoryDto])(_.toDomain)

  /** One branch object, as the branch create answers. */
  val branch: Decode[Branch] =
    WireDecode.single(Json.decoder[BranchDto])(_.toDomain)

  /** A bare array of user objects, as the assignee, reviewer, stargazer and subscriber listings all return. */
  val users: Decode[Vector[User]] =
    listOf(Json.decoder[Vector[UserDto]])((dto, at) => dto.toDomainAt(at))

  /** A bare array of issue objects, as the pinned-issue listing returns. */
  val issues: Decode[Vector[Issue]] =
    listOf(Json.decoder[Vector[IssueDto]])((dto, at) => dto.toDomainAt(at))

  /** A bare array of content entries, as the root contents listing returns. */
  val contents: Decode[Vector[ContentEntry]] =
    listOf(Json.decoder[Vector[ContentEntryDto]])((dto, at) => dto.toDomainAt(at))

  /** A bare array of activity entries. */
  val activities: Decode[Vector[RepositoryActivity]] =
    listOf(Json.decoder[Vector[ActivityDto]])((dto, at) => dto.toDomainAt(at))

  /** A bare array of tracked-time entries, shared with the per-issue listings. */
  val trackedTimes: Decode[Vector[TrackedTime]] =
    listOf(Json.decoder[Vector[TrackedTimeDto]])((dto, at) => dto.toDomainAt(at))

  /** One push-mirror object. */
  val pushMirror: Decode[PushMirror] =
    WireDecode.single(Json.decoder[PushMirrorDto])(_.toDomain)

  /** A bare array of push-mirror objects. */
  val pushMirrors: Decode[Vector[PushMirror]] =
    listOf(Json.decoder[Vector[PushMirrorDto]])((dto, at) => dto.toDomainAt(at))

  /** The subscription object, which only a watcher ever receives — a non-watcher gets a `404`. */
  val watchStatus: Decode[WatchStatus] =
    WireDecode.single(Json.decoder[WatchInfoDto])(dto => Right(dto.toDomain))

  /** The fork-sync description both `sync_fork` reads answer. */
  val forkSyncInfo: Decode[ForkSyncInfo] =
    WireDecode.single(Json.decoder[SyncForkInfoDto])(dto => Right(dto.toDomain))

  /** The two-flag object the pin-allowance read answers. */
  val issuePinsAllowed: Decode[IssuePinsAllowed] =
    WireDecode.single(Json.decoder[IssuePinsAllowedDto])(dto => Right(dto.toDomain))

  /** The bare `{"language": bytes}` object the language statistics answer; see its DTO for why it is special. */
  val languages: Decode[LanguageBreakdown] =
    WireDecode.single(Json.decoder[LanguageStatisticsDto])(dto => Right(dto.toDomain))

  /** The `{"topics": [...]}` envelope the topic search returns, unwrapped to the topics it carries. */
  val topics: Decode[Vector[TopicSummary]] =
    WireDecode.single(Json.decoder[TopicSearchEnvelopeDto]): envelope =>
      TopicSummaryDto.toDomainAll(RepositoryAdminDecoders.TopicEntriesPath, envelope.entries)

  /** The single-file write response, shared with `POST /repos/{owner}/{repo}/diffpatch`. */
  val fileChange: Decode[FileChange] =
    WireDecode.single(Json.decoder[FileResponseDto])(_.toDomain)

  /** The batch write response. */
  val fileChangeSet: Decode[FileChangeSet] =
    WireDecode.single(Json.decoder[FilesResponseDto])(_.toDomain)

  /** The repository's signing key, exactly as the instance sent it.
    *
    * Never parsed, for the reason [[com.worxbend.codeberg4s.miscellaneous.PlainText]] gives: an armored key begins
    * `-----BEGIN`, which is not JSON by any reading. An empty body is a success meaning "this repository signs
    * nothing", not a failure — see [[com.worxbend.codeberg4s.miscellaneous.SigningKey.from]].
    */
  val signingKey: Decode[Option[SigningKey]] =
    PlainText.decodedAs(SigningKey.from)

  /** A response body that is an array of DTOs, converted element by element with each failure at its own index. */
  private def listOf[D, A](wire: Decode[Vector[D]])(
      one: (D, JsonPath) => Either[DecodeFailure, A]
  ): Decode[Vector[A]] =
    WireDecode.single(wire)(dtos => ArrayElements.convert(JsonPath.Root, dtos)(one))
