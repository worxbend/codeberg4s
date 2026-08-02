package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.hooks.HookConfig
import com.worxbend.codeberg4s.repositories.hooks.HookId
import com.worxbend.codeberg4s.repositories.hooks.HookType
import com.worxbend.codeberg4s.repositories.hooks.Webhook
import com.worxbend.codeberg4s.repositories.wire.Elements

/** Forgejo's `Hook` model — one repository webhook.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' Every hook route requires a token and the golden
  * harvest behind `modules/codec/test/resources/golden` was anonymous, so no fixture exists for this shape; the field
  * set below is the spec's `Hook` definition read literally.
  *
  * ==Two wire keys are read in a way worth stating==
  *
  * '''`content_type` appears twice.''' The `Hook` definition declares it both as a top-level property and, implicitly,
  * as an entry of the `config` map that every hook type carries. They are one value. [[toDomainAt]] prefers the config
  * entry and lets the top-level key fill in when the config omits it, so a caller reads one
  * [[com.worxbend.codeberg4s.repositories.hooks.HookConfig.contentType]] whichever spelling the instance chose.
  *
  * '''`authorization_header` is not read at all.''' It is a bearer credential replayed on every delivery, and this DTO
  * deliberately has no field for it: a field that existed would be a value that could reach a log, a
  * [[com.worxbend.codeberg4s.CodebergError]] or a generated `toString`. Adding one "for completeness" is a
  * review-blocking defect. The same goes for anything an instance sends under `secret`, which
  * [[com.worxbend.codeberg4s.repositories.hooks.HookConfig.from]] drops before a config value exists. See
  * [[com.worxbend.codeberg4s.repositories.hooks.HookSecret]] for the discipline both follow.
  *
  * `metadata` is not read either, for a different reason: the spec declares the property with no type whatsoever, so
  * there is nothing to decode into. See [[com.worxbend.codeberg4s.repositories.hooks.Webhook]].
  */
final case class WebhookDto(
    id: Option[Long],
    hookType: Option[String],
    config: Map[String, String],
    contentType: Option[String],
    events: Vector[String],
    url: Option[String],
    branchFilter: Option[String],
    active: Option[Boolean],
    createdAt: Option[String],
    updatedAt: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `id` is the only required field, because it is the only thing that addresses the hook: a webhook that cannot be
    * named cannot be edited, tested or deleted, so a payload without one is a decoding failure rather than a hook with
    * a hole in it. Everything else is absence-tolerant, per rule 2 of
    * [[com.worxbend.codeberg4s.codec.WireConventions]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Webhook] =
    Wire
      .validated(at, "id", id)(HookId.from)
      .map: identifier =>
        Webhook(
          id            = identifier,
          hookType      = hookType.map(HookType.parse),
          configuration = configuration,
          events        = HookWire.events(events),
          url           = url,
          branchFilter  = branchFilter,
          isActive      = active,
          createdAt     = Timestamps.parseOptional(createdAt),
          updatedAt     = Timestamps.parseOptional(updatedAt),
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Webhook] =
    toDomainAt(JsonPath.Root)

  /** The config map with the top-level `content_type` folded in; see the class note on why it appears twice. */
  private def configuration: HookConfig =
    val merged = contentType match
      case Some(value) if !config.contains(HookConfig.ContentTypeKey) =>
        config.updated(HookConfig.ContentTypeKey, value)
      case _                                                          => config

    HookConfig.from(merged)

object WebhookDto:

  /** Reads a `Hook` object. Absent and `null` are the same thing for every field; see [[JsonFields]]. */
  given JsonDecoder[WebhookDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): WebhookDto =
    WebhookDto(
      id           = fields.number("id"),
      hookType     = fields.text("type"),
      config       = HookWire.stringMap(fields, "config"),
      contentType  = fields.text("content_type"),
      events       = fields.texts("events"),
      url          = fields.text("url"),
      branchFilter = fields.text("branch_filter"),
      active       = fields.boolean("active"),
      createdAt    = fields.text("created_at"),
      updatedAt    = fields.text("updated_at"),
    )

  /** Converts a decoded array of hooks, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[WebhookDto]): Either[DecodeFailure, Vector[Webhook]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
