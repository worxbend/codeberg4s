package com.worxbend.codeberg4s.miscellaneous

/** The HTML the instance produced from a markdown document.
  *
  * Wrapped rather than returned as a bare `String` because the two are not interchangeable: this value is markup that a
  * caller is about to put into a page, and a type that says so is what stops it being concatenated with a user's plain
  * text somewhere downstream.
  *
  * '''It is an HTML fragment, not a document.''' There is no `<html>` element and no wrapper; Forgejo returns the
  * rendered body only.
  *
  * '''Trust boundary.''' Forgejo sanitises the output server-side, and this library does not sanitise it again — it
  * cannot, without a HTML parser and a policy it has no business choosing. So the safety of embedding this depends
  * entirely on the instance the client is configured against. Rendering markdown on an instance you do not trust and
  * injecting the result into your own origin is a cross-site-scripting decision, not a formatting one.
  *
  * @param html
  *   the response body verbatim, exactly as the instance rendered it
  */
final case class RenderedMarkdown private[codeberg4s] (html: String)
