package com.worxbend.codeberg4s.miscellaneous

/** Whether the document being rendered is a wiki page.
  *
  * Forgejo spells this as the boolean `Wiki` in the request body. It is an enum here because it reaches the caller as
  * an argument, and `render(text, mode, context, true)` asks every reader and every reviewer to remember what `true`
  * meant — the one Boolean-blindness case `SCALA_CODE_STYLE.md` calls out by name.
  *
  * It changes how relative links are resolved: a wiki page's links point inside the wiki, a regular page's point at the
  * repository tree.
  */
enum MarkdownPageKind:

  /** A document living in the repository itself — a README, an issue body, a release note. */
  case Regular

  /** A page of the repository's wiki. */
  case Wiki

  /** The value Forgejo expects in the `Wiki` field of the request body. */
  def isWiki: Boolean =
    this match
      case Regular => false
      case Wiki    => true
