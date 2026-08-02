package com.worxbend.codeberg4s.miscellaneous

/** Which renderer `POST /markup` runs the document through.
  *
  * The superset of [[MarkdownMode]], and a separate enum rather than a widening of it, because the extra case changes
  * what the request '''means''': [[MarkupMode.File]] tells the instance to choose a renderer from the document's file
  * extension, so a document in that mode is not markdown at all. Adding the case to [[MarkdownMode]] would let it be
  * passed to `POST /markdown`, where the instance has no file name to choose with.
  *
  * The four tokens are the ones `MarkupOption.Mode` documents in the pinned spec.
  */
enum MarkupMode:

  /** How an issue or pull-request comment is rendered: GitHub-flavoured markdown with references resolved and with the
    * heading levels a comment is allowed to use.
    */
  case Comment

  /** GitHub-flavoured markdown — tables, task lists, autolinking, references resolved against the context. */
  case Gfm

  /** Plain markdown with no forge-specific extension. Nothing is resolved against a repository. */
  case Markdown

  /** Whatever renderer the document's extension selects — Org-mode, AsciiDoc, reStructuredText, or markdown again.
    *
    * '''This is the mode that needs [[MarkupRenderRequest.filePath]].''' Without a file name there is no extension to
    * dispatch on, and the instance answers `422`.
    */
  case File

  /** The lowercase token Forgejo expects in the `Mode` field of the request body. */
  def wireName: String =
    this match
      case Comment  => "comment"
      case Gfm      => "gfm"
      case Markdown => "markdown"
      case File     => "file"

  /** Whether this mode requires a file name to dispatch on; see [[MarkupMode.File]]. */
  def needsFilePath: Boolean =
    this match
      case File => true
      case Comment | Gfm | Markdown => false
