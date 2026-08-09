package com.worxbend.codeberg4s.miscellaneous

/** One `.gitignore` template the instance ships — what `GET /gitignore/templates/{name}` answers.
  *
  * These are the templates Forgejo offers when a repository is created, and they are a property of the
  * '''deployment''', not of any repository: the same call answers the same thing for every caller, which is why this
  * lives beside the settings models rather than under `repositories`.
  *
  * '''Derived from `spec/swagger.v1.json`'s `GitignoreTemplateInfo` definition.'''
  * `golden/misc/gitignore-templates.json` is a capture of the '''listing''', which is an array of names and carries no
  * template body, so the two fields below are the spec read literally and not a measured shape.
  *
  * @param name
  *   the template's own name, as the listing reports it. Absent when the instance echoed no name — the caller already
  *   knows which template they asked for, so this is confirmation rather than information
  * @param source
  *   the file's contents, verbatim, newlines and comments included. This is the answer to the question the call asked,
  *   so a payload without it does not decode
  */
final case class GitignoreTemplate private[codeberg4s] (
    name: Option[String],
    source: String,
)
