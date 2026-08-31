package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.Label
import com.worxbend.codeberg4s.issues.LabelColor
import com.worxbend.codeberg4s.issues.LabelId

/** Forgejo's `Label` model, field for field.
  *
  * All seven keys the spec declares appear on all five elements of `golden/issue/labels-repo.json`, on the labels
  * embedded in `golden/issue/list-closed.json` and `golden/issue/search.json`, and on
  * `golden/organization/org-labels-list.json`. `golden/issue/labels-on-issue-empty.json` is the other arm: an empty
  * array, which is what an unlabelled issue's `labels` endpoint returns.
  *
  * `color` stays a raw string here. [[com.worxbend.codeberg4s.issues.LabelColor]] normalises it during conversion, and
  * does so leniently — see [[toDomainAt]].
  */
final case class LabelDto(
    id: Option[Long],
    name: Option[String],
    color: Option[String],
    description: Option[String],
    exclusive: Option[Boolean],
    isArchived: Option[Boolean],
    url: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Two things are required: `id`, because it is the only way to address a label, and `name`, because a label with no
    * text is not a label. `id` additionally goes through [[com.worxbend.codeberg4s.issues.LabelId.from]], so a
    * non-positive id is reported here rather than turning into a request for `/labels/0`.
    *
    * `color` is the one deliberately '''lenient''' field: a value [[com.worxbend.codeberg4s.issues.LabelColor.from]]
    * rejects becomes `None` instead of failing the label. Every colour in the fixtures is a bare six-digit triplet, but
    * the colour is decoration, and losing a whole page of labels because one of them carries something unexpected would
    * be a bad trade.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Label] =
    for
      identifier <- Wire.validated(at, "id", id)(LabelId.from)
      text       <- Wire.required(at, "name", name)
    yield Label(
      id          = identifier,
      name        = text,
      color       = color.flatMap(value => LabelColor.from(value).toOption),
      description = description,
      isExclusive = exclusive.getOrElse(false),
      isArchived  = isArchived.getOrElse(false),
      url         = url,
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Label] =
    toDomainAt(JsonPath.Root)

object LabelDto:

  /** Reads a `Label` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[LabelDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. Used by the reader above and by every DTO that embeds a label, so the field
    * spellings exist in exactly one place.
    */
  def fromFields(fields: JsonFields): LabelDto =
    LabelDto(
      id          = fields.number("id"),
      name        = fields.text("name"),
      color       = fields.text("color"),
      description = fields.text("description"),
      exclusive   = fields.boolean("exclusive"),
      isArchived  = fields.boolean("is_archived"),
      url         = fields.text("url"),
    )

  /** Converts a decoded array of labels, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[LabelDto]): Either[DecodeFailure, Vector[Label]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
