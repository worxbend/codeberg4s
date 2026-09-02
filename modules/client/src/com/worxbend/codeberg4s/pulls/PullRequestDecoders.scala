package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.pulls.wire.{ChangedFileDto, PullRequestDto, ReviewCommentDto, ReviewDto}
import com.worxbend.codeberg4s.repositories.Commit
import com.worxbend.codeberg4s.repositories.wire.CommitDto

/** Every response shape [[PullRequestApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call: a decoder is a function
  * from a body to a value, and allocating a new one per request would be waste with no upside.
  *
  * Nothing in this group uses an envelope: every listing is a bare JSON array, so element failures are reported at
  * `$[n]` throughout, never at `$.data[n]`.
  */
private[pulls] object PullRequestDecoders:

  /** One pull-request object, as `GET /repos/{owner}/{repo}/pulls/{index}` returns it. */
  val pullRequest: Decode[PullRequest] =
    WireDecode.single(Json.decoder[PullRequestDto])(_.toDomain)

  /** A bare array of pull-request objects, as the listing returns it. */
  val pullRequests: Decode[Vector[PullRequest]] =
    WireDecode.vector(Json.decoder[Vector[PullRequestDto]])

  /** One review object. */
  val review: Decode[Review] =
    WireDecode.single(Json.decoder[ReviewDto])(_.toDomain)

  /** A bare array of review objects. */
  val reviews: Decode[Vector[Review]] =
    WireDecode.vector(Json.decoder[Vector[ReviewDto]])

  /** One review comment. */
  val reviewComment: Decode[ReviewComment] =
    WireDecode.single(Json.decoder[ReviewCommentDto])(_.toDomain)

  /** A bare array of review comments. */
  val reviewComments: Decode[Vector[ReviewComment]] =
    WireDecode.vector(Json.decoder[Vector[ReviewCommentDto]])

  /** A bare array of commit objects, read by the model `client.repos` already uses. */
  val commits: Decode[Vector[Commit]] =
    WireDecode.vector(Json.decoder[Vector[CommitDto]])

  /** A bare array of changed-file entries — the diff summary, not the diff. */
  val files: Decode[Vector[ChangedFile]] =
    WireDecode.vector(Json.decoder[Vector[ChangedFileDto]])
