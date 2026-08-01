package com.worxbend.codeberg4s.miscellaneous

/** A document to render, and how to render it — the argument of `POST /markdown`.
  *
  * A named type rather than four parameters on the method: the two enums and the optional context are exactly the knobs
  * that decide whether `#123` becomes a link, and grouping them makes a call site say which rendering it wanted.
  *
  * @param text
  *   the markdown source. May be empty, which renders to an empty document rather than failing
  * @param mode
  *   which extensions to render with; see [[MarkdownMode]]
  * @param context
  *   the repository references are resolved against, absent when the document stands alone
  * @param page
  *   whether the document is a wiki page, which changes how relative links are resolved
  */
final case class MarkdownRenderRequest(
    text: String,
    mode: MarkdownMode,
    context: Option[MarkdownContext],
    page: MarkdownPageKind,
)

object MarkdownRenderRequest:

  /** A standalone document: plain [[MarkdownMode.Markdown]], no repository context, not a wiki page.
    *
    * The same rendering `POST /markdown/raw` performs, which makes this the request to start from and `copy` when only
    * one knob needs turning.
    */
  def of(text: String): MarkdownRenderRequest =
    MarkdownRenderRequest(text, MarkdownMode.Markdown, None, MarkdownPageKind.Regular)
