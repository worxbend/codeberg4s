package com.worxbend.codeberg4s.repositories.gitdata

/** The EditorConfig properties in force for one file — `GET /repos/{owner}/{repo}/editorconfig/{filepath}`.
  *
  * The instance resolves the repository's `.editorconfig` files for the requested path and answers with the properties
  * that apply, already merged: `indent_style`, `indent_size`, `charset`, `insert_final_newline` and whatever else the
  * repository declares. There is no fixed set, which is why this is a map and not a record — a repository may declare
  * any property name at all, and a model with named fields would silently drop the ones it did not anticipate.
  *
  * ==Values are text, including the ones that look like numbers==
  *
  * EditorConfig is a text format and the spec types every value as a string. A value that arrives as a JSON number or
  * boolean is rendered back to its text form here rather than dropped, so `indent_size` reads as `"4"` and
  * `insert_final_newline` as `"true"` whichever way the instance chose to encode it. Interpreting those is the caller's
  * business: `indent_size` is legitimately the word `tab` as often as it is a number.
  *
  * @param values
  *   the properties exactly as the instance named them, in no particular order
  */
final case class EditorConfigDefinitions private[codeberg4s] (values: Map[String, String]):

  /** The value of `property`, matched case-insensitively.
    *
    * EditorConfig property names are case-insensitive by specification and Forgejo lowercases them on the way out, but
    * a caller should not have to know that to look one up.
    */
  def valueOf(property: String): Option[String] =
    val wanted = property.trim

    values.collectFirst { case (name, value) if name.equalsIgnoreCase(wanted) => value }

  /** Whether the instance found no properties for the path. Not an error: a repository with no `.editorconfig`, or one
    * whose sections do not match this path, legitimately has nothing to say about it.
    */
  def isEmpty: Boolean = values.isEmpty
