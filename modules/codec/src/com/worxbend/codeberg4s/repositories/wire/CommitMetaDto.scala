package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.{CommitRef, CommitSha}

/** Forgejo's `CommitMeta` — a commit or tree referenced by id.
  *
  * Appears as a tag's `commit`, a commit's `tree`, and every element of a commit's `parents`.
  *
  * @param url
  *   the `url` key
  * @param sha
  *   the `sha` key
  * @param created
  *   the `created` key as a raw string. Forgejo sends its zero-time sentinel here for a tree, which
  *   [[com.worxbend.codeberg4s.codec.Timestamps]] folds into absence during conversion
  */
final case class CommitMetaDto(url: Option[String], sha: Option[String], created: Option[String])
    extends WireModel[CommitRef]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Fails only on `sha`, and only because a reference without the thing it references is not a reference. The value
    * additionally goes through [[com.worxbend.codeberg4s.repositories.CommitSha.from]], so a non-hexadecimal id is
    * rejected here rather than forging a path later.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, CommitRef] =
    Wire
      .validated(at, "sha", sha)(CommitSha.from)
      .map(id => CommitRef(sha = id, url = url, created = Timestamps.parseOptional(created)))

object CommitMetaDto:

  /** Reads a `CommitMeta` object. */
  given JsonDecoder[CommitMetaDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the DTOs that embed this one. */
  def fromFields(fields: JsonFields): CommitMetaDto =
    CommitMetaDto(
      url     = fields.text("url"),
      sha     = fields.text("sha"),
      created = fields.text("created"),
    )
