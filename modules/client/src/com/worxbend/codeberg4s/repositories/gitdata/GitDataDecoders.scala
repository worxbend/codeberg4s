package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.miscellaneous.PlainText
import com.worxbend.codeberg4s.pulls.PullRequest
import com.worxbend.codeberg4s.pulls.wire.PullRequestDto
import com.worxbend.codeberg4s.repositories.Commit
import com.worxbend.codeberg4s.repositories.gitdata.wire.AnnotatedTagDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.CombinedStatusDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.CommitStatusDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.CompareDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.EditorConfigDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.FileResponseDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.GitBlobDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.GitTreeDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.NoteDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.ReferenceDto
import com.worxbend.codeberg4s.repositories.wire.CommitDto

/** Every response shape [[RepositoryGitApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call.
  *
  * ==Three envelope shapes and one non-envelope==
  *
  * Most of this group answers with a bare object or a bare array. Two do not: the tree listing wraps its entries in
  * `{"sha", "tree", "truncated", …}` and the combined status wraps its statuses in an object that also carries the
  * reduced verdict. The first is unwrapped here, because a tree listing is a page like any other; the second is not,
  * because unwrapping it would throw away the verdict that is the point of the endpoint. See
  * [[com.worxbend.codeberg4s.repositories.gitdata.CombinedCommitStatus]].
  *
  * And one endpoint group answers with no JSON at all — `{sha}.diff`, `{sha}.patch`, `/raw`, `/media` and `/archive` —
  * which is what [[com.worxbend.codeberg4s.miscellaneous.PlainText]] is for. Running a diff through a JSON parser turns
  * a perfectly good response into a decoding failure.
  */
private[gitdata] object GitDataDecoders:

  /** One `GitBlob` object. */
  val blob: Decode[GitBlob] =
    WireDecode.single(Json.decoder[GitBlobDto])(_.toDomain)

  /** A bare array of `GitBlob` objects, as the multi-blob read returns it. */
  val blobs: Decode[Vector[GitBlob]] =
    WireDecode.vector(Json.decoder[Vector[GitBlobDto]]): (at, dtos) =>
      ArrayElements.convert(at, dtos)(_.toDomainAt(_))

  /** The `{"sha", "tree", …}` envelope, unwrapped to the entries it carries. */
  val treeEntries: Decode[Vector[GitTreeEntry]] =
    WireDecode.single(Json.decoder[GitTreeDto])(_.toDomain)

  /** One `Commit` object, as the single-commit read returns it. */
  val commit: Decode[Commit] =
    WireDecode.single(Json.decoder[CommitDto])(_.toDomain)

  /** One `Note` object. */
  val note: Decode[GitNote] =
    WireDecode.single(Json.decoder[NoteDto])(_.toDomain)

  /** A bare array of `Reference` objects. */
  val references: Decode[Vector[GitReference]] =
    WireDecode.vector(Json.decoder[Vector[ReferenceDto]]): (at, dtos) =>
      ArrayElements.convert(at, dtos)(_.toDomainAt(_))

  /** One `AnnotatedTag` object. */
  val annotatedTag: Decode[AnnotatedTag] =
    WireDecode.single(Json.decoder[AnnotatedTagDto])(_.toDomain)

  /** One `CombinedStatus` object, kept whole — see this object's own note. */
  val combinedStatus: Decode[CombinedCommitStatus] =
    WireDecode.single(Json.decoder[CombinedStatusDto])(_.toDomain)

  /** A bare array of `CommitStatus` objects. */
  val commitStatuses: Decode[Vector[CommitStatus]] =
    WireDecode.vector(Json.decoder[Vector[CommitStatusDto]]): (at, dtos) =>
      ArrayElements.convert(at, dtos)(_.toDomainAt(_))

  /** One `PullRequest` object, reusing the pull-request wave's model rather than a reduced copy of it. */
  val pullRequest: Decode[PullRequest] =
    WireDecode.single(Json.decoder[PullRequestDto])(_.toDomain)

  /** One `Compare` object. */
  val comparison: Decode[CommitComparison] =
    WireDecode.single(Json.decoder[CompareDto])(_.toDomain)

  /** One `FileResponse` object, as the diffpatch write answers with. */
  val fileChange: Decode[FileChange] =
    WireDecode.single(Json.decoder[FileResponseDto])(_.toDomain)

  /** The EditorConfig definitions object, whose property names are not known in advance. */
  val editorConfig: Decode[EditorConfigDefinitions] =
    WireDecode.single(Json.decoder[EditorConfigDto])(dto => Right(dto.toDomain))

  /** A body that is not JSON: a diff, a patch, or a file this transport could only decode as text.
    *
    * Never fails — see [[com.worxbend.codeberg4s.miscellaneous.PlainText]].
    */
  val text: Decode[String] =
    PlainText.decoder
