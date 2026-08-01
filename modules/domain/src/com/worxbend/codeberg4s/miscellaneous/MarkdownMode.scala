package com.worxbend.codeberg4s.miscellaneous

/** Which set of markdown extensions the instance renders with.
  *
  * The three cases are the ones `MarkdownOption.Mode` documents in the pinned spec. They are not cosmetic variants:
  * `Comment` and `Gfm` resolve issue references, mentions and emoji against the repository named by
  * [[MarkdownContext]], and `Markdown` does not, so a document rendered in the wrong mode silently loses its links.
  */
enum MarkdownMode:

  /** How an issue or pull-request comment is rendered: GitHub-flavoured markdown with references resolved and with the
    * heading levels a comment is allowed to use.
    */
  case Comment

  /** GitHub-flavoured markdown — tables, task lists, autolinking, references resolved against the context. What a
    * repository's `README.md` is rendered with.
    */
  case Gfm

  /** Plain markdown with no forge-specific extension. Nothing is resolved against a repository. */
  case Markdown

  /** The lowercase token Forgejo expects in the `Mode` field of the request body. */
  def wireName: String =
    this match
      case Comment  => "comment"
      case Gfm      => "gfm"
      case Markdown => "markdown"
