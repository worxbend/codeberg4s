package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.admin.MirrorName
import com.worxbend.codeberg4s.repositories.admin.PushMirror
import com.worxbend.codeberg4s.repositories.wire.Elements

/** Forgejo's `PushMirror` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' Every push-mirror endpoint requires a token
  * and `modules/codec/test/resources/golden` was harvested anonymously, so no fixture backs this shape. All ten
  * declared properties are represented.
  *
  * `created` and `lastUpdate` stay as raw strings here; [[com.worxbend.codeberg4s.codec.Timestamps]] turns them into
  * instants during conversion, where Forgejo's zero-time sentinel is folded into absence — which is exactly what a
  * mirror that has never run reports for `last_update`.
  *
  * @param remoteName
  *   the `remote_name` key
  * @param remoteAddress
  *   the `remote_address` key
  * @param repoName
  *   the `repo_name` key
  * @param branchFilter
  *   the `branch_filter` key
  * @param interval
  *   the `interval` key
  * @param syncOnCommit
  *   the `sync_on_commit` key
  * @param lastError
  *   the `last_error` key
  * @param publicKey
  *   the `public_key` key
  * @param created
  *   the `created` key as a raw string
  * @param lastUpdate
  *   the `last_update` key as a raw string
  */
final case class PushMirrorDto(
    remoteName: Option[String],
    remoteAddress: Option[String],
    repoName: Option[String],
    branchFilter: Option[String],
    interval: Option[String],
    syncOnCommit: Option[Boolean],
    lastError: Option[String],
    publicKey: Option[String],
    created: Option[String],
    lastUpdate: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Fails only on `remote_name`, through [[com.worxbend.codeberg4s.repositories.admin.MirrorName.from]]: it is the
    * only way to address the mirror afterwards, so a mirror without one cannot be acted on and is not worth handing
    * back. Everything else is genuinely optional and stays optional — a mirror that has never run has no `last_update`,
    * one authenticated by password has no `public_key`, and a healthy one sends `""` for `last_error`, which
    * [[com.worxbend.codeberg4s.codec.JsonFields.text]] has already folded into absence.
    *
    * `sync_on_commit` absent becomes `false`, which is the same answer Forgejo gives for a mirror that does not do it.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, PushMirror] =
    Wire
      .validated(at, "remote_name", remoteName)(MirrorName.from)
      .map: name =>
        PushMirror(
          remoteName    = name,
          remoteAddress = remoteAddress,
          repoName      = repoName,
          branchFilter  = branchFilter,
          interval      = interval,
          syncsOnCommit = syncOnCommit.getOrElse(false),
          lastError     = lastError,
          publicKey     = publicKey,
          createdAt     = Timestamps.parseOptional(created),
          lastUpdateAt  = Timestamps.parseOptional(lastUpdate),
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, PushMirror] =
    toDomainAt(JsonPath.Root)

object PushMirrorDto:

  /** Reads a `PushMirror` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[PushMirrorDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): PushMirrorDto =
    PushMirrorDto(
      remoteName    = fields.text("remote_name"),
      remoteAddress = fields.text("remote_address"),
      repoName      = fields.text("repo_name"),
      branchFilter  = fields.text("branch_filter"),
      interval      = fields.text("interval"),
      syncOnCommit  = fields.boolean("sync_on_commit"),
      lastError     = fields.text("last_error"),
      publicKey     = fields.text("public_key"),
      created       = fields.text("created"),
      lastUpdate    = fields.text("last_update"),
    )

  /** Converts a whole array, each element failing at its own index. */
  def toDomainAll(base: JsonPath, dtos: Vector[PushMirrorDto]): Either[DecodeFailure, Vector[PushMirror]] =
    Elements.convert(base, dtos)((dto, at) => dto.toDomainAt(at))
