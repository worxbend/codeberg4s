package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.miscellaneous.MarkupRenderRequest

/** Forgejo's `MarkupOption` model — the '''request''' body of `POST /markup`.
  *
  * Written rather than read, so the same two conventions read backwards here as in [[MarkdownOptionDto]]: no `Reader`,
  * no `toDomain`, and instead a [[fromDomain]] that narrows a domain request into the wire shape and a [[toJson]] that
  * renders it. Rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] still holds — each wire name is spelled
  * exactly once, in [[toJson]].
  *
  * '''The field names are capitalised, and that is not a typo''' — `MarkupOption` carries no JSON tags either, so Go's
  * marshaller uses the field names verbatim. See [[MarkdownOptionDto]] for the full argument; this model adds
  * `FilePath` and `BranchPath` to the four that model shares.
  *
  * '''Derived from `spec/swagger.v1.json`'s `MarkupOption` definition.''' `golden/MANIFEST.md` records that the
  * markdown endpoints answered `401` anonymously on codeberg.org, so neither renderer has a captured request or
  * response.
  *
  * @param text
  *   the markup source
  * @param mode
  *   the lowercase mode token, from [[com.worxbend.codeberg4s.miscellaneous.MarkupMode.wireName]]
  * @param context
  *   the repository references resolve against, omitted from the body entirely when absent
  * @param filePath
  *   the file name `file` mode dispatches on, omitted when absent
  * @param branchPath
  *   the branch relative links resolve against, omitted when absent
  * @param wiki
  *   whether the document is a wiki page
  */
final case class MarkupOptionDto(
    text: String,
    mode: String,
    context: Option[String],
    filePath: Option[String],
    branchPath: Option[String],
    wiki: Boolean,
):

  /** Renders the body Forgejo expects, with the three optional keys present only when the caller supplied them.
    *
    * Cannot fail: every field is already a plain JSON value, and the JSON parser escapes the strings — which is why
    * [[com.worxbend.codeberg4s.miscellaneous.MarkupRenderRequest]] does not validate the two paths.
    */
  def toJson: String =
    val fields = Vector[(String, JsonValue)](
      "Text" -> JsonValue.Str(text),
      "Mode" -> JsonValue.Str(mode),
      "Wiki" -> JsonValue.Bool(wiki),
    ) ++
      context.map(value => "Context" -> JsonValue.Str(value)) ++
      filePath.map(value => "FilePath" -> JsonValue.Str(value)) ++
      branchPath.map(value => "BranchPath" -> JsonValue.Str(value))

    Json.render(JsonValue.Obj.from(fields))

object MarkupOptionDto:

  /** Narrows a domain request into the wire shape, collapsing both enums into what Forgejo reads. */
  def fromDomain(request: MarkupRenderRequest): MarkupOptionDto =
    MarkupOptionDto(
      text       = request.text,
      mode       = request.mode.wireName,
      context    = request.context.map(_.value),
      filePath   = request.filePath,
      branchPath = request.branchPath,
      wiki       = request.page.isWiki,
    )
