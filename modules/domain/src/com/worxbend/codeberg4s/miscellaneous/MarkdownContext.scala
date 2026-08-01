package com.worxbend.codeberg4s.miscellaneous

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.RepoSlug

/** The repository a rendered document's relative links and references are resolved against.
  *
  * Forgejo calls it `Context` and uses it as the base of every link it rewrites, so `#123`, `@someone` and
  * `![](img.png)` only become useful markup when it is supplied. Without one they are rendered as literal text.
  *
  * The value travels in the JSON '''body''', never in the request path, so nothing here is defending against a forged
  * path the way [[com.worxbend.codeberg4s.repositories.Owner]] does. What it does defend against is a value that would
  * corrupt the request itself: a control character or a line break in a JSON string is the kind of input that turns a
  * render call into a puzzle, and it is rejected at construction instead.
  */
opaque type MarkdownContext = String

object MarkdownContext:

  /** The context of a repository on the instance being talked to, as `owner/name`.
    *
    * This is the common case and it cannot fail, because [[com.worxbend.codeberg4s.repositories.RepoSlug]] is already
    * validated.
    */
  def of(slug: RepoSlug): MarkdownContext =
    slug.value

  /** Parses a context Forgejo will accept.
    *
    * Forgejo takes either a repository path such as `owner/name` or an absolute URL, and documents neither, so this
    * constructor does not try to choose between them: it trims the value and rejects only what could not work at all —
    * a blank string, and any string carrying a control character or a line break.
    *
    * @return
    *   the context, or a [[com.worxbend.codeberg4s.ValidationError]] on the `"markdownContext"` field
    */
  def from(value: String): Either[ValidationError, MarkdownContext] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError("markdownContext", "must not be blank"))
    else if trimmed.exists(_.isControl) then
      Left(ValidationError("markdownContext", "must not contain a control character"))
    else Right(trimmed)

  extension (context: MarkdownContext)

    /** The context as a `String`, ready for the `Context` field of the request body. */
    def value: String = context
