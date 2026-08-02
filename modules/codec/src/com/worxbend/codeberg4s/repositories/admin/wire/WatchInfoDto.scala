package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.repositories.admin.WatchStatus

/** Forgejo's `WatchInfo` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' The endpoint describes the '''calling'''
  * account, so an anonymous harvest could not have captured it. All six declared properties are represented.
  *
  * ==`reason` has no type on the wire==
  *
  * The spec declares the property with an `x-go-name` and nothing else — no `type`, no `$ref` — because the Go field is
  * an `interface{}` that Forgejo currently always sets to `nil`. It is read here as text, which means a JSON `null`
  * (what Forgejo sends), an absent key, an object and a number all arrive as `None`, and only a string survives. That
  * is the leniency [[com.worxbend.codeberg4s.codec.JsonFields]] applies everywhere, and it is the right one for a field
  * whose shape the spec declines to state.
  *
  * @param subscribed
  *   the `subscribed` key
  * @param ignored
  *   the `ignored` key
  * @param reason
  *   the `reason` key, read as text; see the note above
  * @param url
  *   the `url` key
  * @param repositoryUrl
  *   the `repository_url` key
  * @param createdAt
  *   the `created_at` key as a raw string
  */
final case class WatchInfoDto(
    subscribed: Option[Boolean],
    ignored: Option[Boolean],
    reason: Option[String],
    url: Option[String],
    repositoryUrl: Option[String],
    createdAt: Option[String],
):

  /** Converts to the domain. Cannot fail.
    *
    * Nothing here is required: the endpoint answers `404` rather than a body when the account does not watch the
    * repository, so a body that arrived at all already means "watching", and the two Booleans absent are read as
    * `false` — the same answer Forgejo gives for a subscription it did not flag.
    */
  def toDomain: WatchStatus =
    WatchStatus(
      subscribed    = subscribed.getOrElse(false),
      ignored       = ignored.getOrElse(false),
      reason        = reason,
      url           = url,
      repositoryUrl = repositoryUrl,
      createdAt     = Timestamps.parseOptional(createdAt),
    )

object WatchInfoDto:

  /** Reads a `WatchInfo` object. Absent and `null` are the same thing for every field. */
  given JsonDecoder[WatchInfoDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): WatchInfoDto =
    WatchInfoDto(
      subscribed    = fields.boolean("subscribed"),
      ignored       = fields.boolean("ignored"),
      reason        = fields.text("reason"),
      url           = fields.text("url"),
      repositoryUrl = fields.text("repository_url"),
      createdAt     = fields.text("created_at"),
    )
