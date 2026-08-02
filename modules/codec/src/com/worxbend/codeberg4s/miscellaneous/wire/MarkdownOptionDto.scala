package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.miscellaneous.MarkdownRenderRequest

/** Forgejo's `MarkdownOption` model — the '''request''' body of `POST /markdown`.
  *
  * The first DTO in this module that is written rather than read, so two of the
  * [[com.worxbend.codeberg4s.codec.WireConventions]] rules read backwards here: there is no `Reader` and no `toDomain`,
  * and instead a [[fromDomain]] that narrows a domain request into the wire shape and a [[toJson]] that renders it.
  * Rule 4 still holds — each wire name is spelled exactly once, in [[toJson]].
  *
  * '''The field names are capitalised, and that is not a typo.''' `MarkdownOption` is one of the few Forgejo structs
  * whose Go fields carry no JSON tag, so the marshaller uses the Go field names verbatim: `Text`, `Mode`, `Context`,
  * `Wiki`. The pinned spec declares them that way too. Go's unmarshaller happens to match field names
  * case-insensitively, so a lowercase body would also be accepted today — this sends what the spec documents rather
  * than what the implementation currently tolerates.
  *
  * @param text
  *   the markdown source
  * @param mode
  *   the lowercase mode token, from [[com.worxbend.codeberg4s.miscellaneous.MarkdownMode.wireName]]
  * @param context
  *   the repository references resolve against, omitted from the body entirely when absent
  * @param wiki
  *   whether the document is a wiki page
  */
final case class MarkdownOptionDto(
    text: String,
    mode: String,
    context: Option[String],
    wiki: Boolean,
):

  /** Renders the body Forgejo expects, with `Context` present only when there is one.
    *
    * Cannot fail: every field is already a plain JSON value, and the JSON parser escapes the strings.
    */
  def toJson: String =
    val fields = Vector[(String, JsonValue)](
      "Text" -> JsonValue.Str(text),
      "Mode" -> JsonValue.Str(mode),
      "Wiki" -> JsonValue.Bool(wiki),
    ) ++ context.map(value => "Context" -> JsonValue.Str(value))

    Json.render(JsonValue.Obj.from(fields))

object MarkdownOptionDto:

  /** Narrows a domain request into the wire shape, collapsing both enums into what Forgejo reads. */
  def fromDomain(request: MarkdownRenderRequest): MarkdownOptionDto =
    MarkdownOptionDto(
      text    = request.text,
      mode    = request.mode.wireName,
      context = request.context.map(_.value),
      wiki    = request.page.isWiki,
    )
