package com.worxbend.codeberg4s.miscellaneous

/** A document to render and how to render it — the argument of `POST /markup`.
  *
  * The generalisation of [[MarkdownRenderRequest]]: `/markup` renders whatever markup language the instance supports,
  * not only markdown, so it takes two knobs the markdown endpoint has no use for. A caller who is rendering markdown
  * should keep using [[com.worxbend.codeberg4s.miscellaneous.MiscellaneousApi.renderMarkdown]] — it is the narrower
  * request, and a narrower request cannot be got wrong in the ways this one can.
  *
  * '''Neither [[filePath]] nor [[branchPath]] is a validated path type, and that is deliberate.''' Both travel in the
  * JSON '''body''', never in the request path, so neither can forge a request the way
  * [[com.worxbend.codeberg4s.repositories.Owner]] could: whatever they contain is escaped into a JSON string by the
  * renderer and arrives at the instance verbatim. There is therefore nothing for a smart constructor to defend against,
  * and a validator here would only refuse file names Forgejo would have accepted. [[context]] is a [[MarkdownContext]]
  * because that type already exists and is already validated, not because the body needs it to be.
  *
  * @param text
  *   the markup source. May be empty, which renders to an empty document rather than failing
  * @param mode
  *   which renderer to run; see [[MarkupMode]]
  * @param context
  *   the repository references are resolved against, absent when the document stands alone
  * @param filePath
  *   the document's file name, which is what [[MarkupMode.File]] dispatches on — `README.org`, `docs/guide.adoc`.
  *   Ignored by the other three modes
  * @param branchPath
  *   the branch relative links are resolved against, which the spec describes as "the current branch path where the
  *   form gets posted". Absent leaves the instance to use the repository's default
  * @param page
  *   whether the document is a wiki page, which changes how relative links are resolved
  */
final case class MarkupRenderRequest(
    text: String,
    mode: MarkupMode,
    context: Option[MarkdownContext],
    filePath: Option[String],
    branchPath: Option[String],
    page: MarkdownPageKind,
):

  /** The same request rendered as the named file, which is how [[MarkupMode.File]] is told what it is looking at. */
  def asFile(path: String): MarkupRenderRequest = copy(filePath = Some(path))

  /** The same request with relative links resolved against `branch`. */
  def onBranch(branch: String): MarkupRenderRequest = copy(branchPath = Some(branch))

  /** The same request resolved against a repository; see [[MarkdownContext]]. */
  def inContext(repository: MarkdownContext): MarkupRenderRequest = copy(context = Some(repository))

  /** The same request treated as a wiki page rather than as a document in the repository tree. */
  def asWikiPage: MarkupRenderRequest = copy(page = MarkdownPageKind.Wiki)

object MarkupRenderRequest:

  /** A standalone document in `mode`: no repository context, no file name, not a wiki page.
    *
    * The starting point to `copy` — or to refine with [[MarkupRenderRequest.asFile]] and its siblings — when only one
    * knob needs turning.
    *
    * Total rather than validated: every string is a legal markup document, and nothing here can fail. A
    * [[MarkupMode.File]] request left without a file name is rejected by the '''instance''' with a `422`, not here,
    * because this library does not know which extensions a given deployment has renderers for.
    */
  def of(text: String, mode: MarkupMode): MarkupRenderRequest =
    MarkupRenderRequest(
      text       = text,
      mode       = mode,
      context    = None,
      filePath   = None,
      branchPath = None,
      page       = MarkdownPageKind.Regular,
    )

  /** The request that renders `text` as the file named `path`, in [[MarkupMode.File]].
    *
    * The one combination that is easy to get wrong — [[MarkupMode.File]] without a file name is a `422` — written out
    * so it cannot be.
    */
  def ofFile(text: String, path: String): MarkupRenderRequest =
    of(text, MarkupMode.File).asFile(path)
