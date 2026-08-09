package com.worxbend.codeberg4s.core

import scala.annotation.tailrec

import java.util.Locale
import java.util.regex.Pattern

/** RFC 5988 `Link` header parsing.
  *
  * This is the '''only''' trustworthy pagination signal Forgejo emits. The instance silently clamps `limit` to its
  * `max_response_items` setting — 50 on codeberg.org — while echoing the limit that was asked for back in the `Link`
  * header, so `items.size < requestedLimit` is a broken end-of-collection test: a caller asking for 100 gets 50 items
  * on every page and would stop after the first. A walk ends when, and only when, the response carries no `rel="next"`.
  *
  * '''Failure contract.''' Parsing never fails. A header this object cannot read yields an empty map, and a single
  * unreadable element is skipped while its neighbours are still returned. A header is metadata about a response that
  * already succeeded; failing the call because a proxy rewrote it would turn a cosmetic problem into an outage.
  */
object LinkHeader:

  /** The header name, lowercased to match the keys a transport adapter normalises response headers to. */
  val Name: String = "link"

  /** The relation type of the following page. */
  val Next: String = "next"

  /** The relation type of the preceding page, as Forgejo spells it. */
  val Prev: String = "prev"

  /** The relation type of the preceding page as some proxies spell it; accepted as a synonym for [[Prev]]. */
  val Previous: String = "previous"

  /** The relation type of the first page. */
  val First: String = "first"

  /** The relation type of the final page. */
  val Last: String = "last"

  private val RelParameter: String = "rel"

  // Compiled once, at class-initialisation time. Passing the pattern as a String to String#split would
  // make java.util.regex recompile it on every element of every header.
  private val Whitespace: Pattern = Pattern.compile("\\s+")

  /** Parses a raw `Link` header value into a map from relation type to target URI.
    *
    * Relation types are lowercased, because RFC 8288 defines them case-insensitively. An element declaring several
    * space-separated relation types contributes one entry per type. When a relation type appears twice the first
    * occurrence wins, which is what a client following the header in document order would do.
    *
    * Both quoted and unquoted `rel` values are accepted — the quotes are optional per the RFC and Forgejo happens to
    * send them — and elements may be separated by a comma with or without surrounding space. A comma inside the
    * angle-bracketed target does not split an element.
    *
    * @param value
    *   the raw header value, possibly several headers already joined with commas
    * @return
    *   the readable links, empty when nothing in `value` could be read
    */
  def parse(value: String): Map[String, String] =
    elementsOf(value)
      .flatMap(entriesOf)
      .foldLeft(Map.empty[String, String]):
        case (found, (rel, target)) => if found.contains(rel) then found else found.updated(rel, target)

  /** The first value of the query parameter `name` in `target`, when it carries one.
    *
    * The value is returned exactly as it appears in the URI: nothing is percent-decoded, because the only parameter
    * this library reads out of a link is `page`, whose value is a decimal integer. A target with no query string, or
    * without the parameter, yields `None` rather than an error.
    */
  def queryParameter(target: String, name: String): Option[String] =
    val start = target.indexOf('?')
    if start < 0 then None
    else
      queryOf(target.substring(start + 1))
        .split('&')
        .toList
        .flatMap(part => pairOf(part).toList)
        .collectFirst { case (key, value) if key.equalsIgnoreCase(name) => value }

  private def queryOf(afterQuestionMark: String): String =
    val fragment = afterQuestionMark.indexOf('#')
    if fragment < 0 then afterQuestionMark else afterQuestionMark.take(fragment)

  private def pairOf(part: String): Option[(String, String)] =
    val separator = part.indexOf('=')
    if separator < 0 then None else Some((part.take(separator).trim, part.drop(separator + 1).trim))

  /** Splits on the commas that separate elements, ignoring any comma inside an angle-bracketed target URI. */
  private def elementsOf(value: String): List[String] =
    split(value, 0, 0, false, Nil).reverse.map(_.trim).filter(_.nonEmpty)

  /** The elements of `value`, '''in reverse order''' — [[elementsOf]] puts them back.
    *
    * Prepending onto a list costs the same however long the list is, whereas appending walks it to the end, so a loop
    * that appends `k` times does work proportional to `k²`. Collecting in reverse and turning the result around once at
    * the end keeps the whole split proportional to the length of the header.
    */
  @tailrec
  private def split(value: String, from: Int, at: Int, inTarget: Boolean, reversed: List[String]): List[String] =
    if at >= value.length then value.substring(from) :: reversed
    else
      value.charAt(at) match
        case '<'              => split(value, from, at + 1, true, reversed)
        case '>'              => split(value, from, at + 1, false, reversed)
        case ',' if !inTarget => split(value, at + 1, at + 1, false, value.substring(from, at) :: reversed)
        case _                => split(value, from, at + 1, inTarget, reversed)

  private def entriesOf(element: String): List[(String, String)] =
    val found =
      for
        target <- targetOf(element)
        rels   <- relsOf(element)
      yield rels.map(rel => (rel, target))
    found.getOrElse(Nil)

  private def targetOf(element: String): Option[String] =
    val start = element.indexOf('<')
    val end   = if start < 0 then -1 else element.indexOf('>', start + 1)
    if start < 0 || end < 0 then None else Some(element.substring(start + 1, end).trim).filter(_.nonEmpty)

  private def relsOf(element: String): Option[List[String]] =
    parametersOf(element)
      // equalsIgnoreCase rather than ==, because RFC 5988 parameter names are
      // case-insensitive and .scalafix.conf bans universal equality.
      .collectFirst { case (name, value) if name.equalsIgnoreCase(RelParameter) => unquote(value) }
      .map(declared => Whitespace.split(declared).toList.map(_.toLowerCase(Locale.ROOT)).filter(_.nonEmpty))
      .filter(_.nonEmpty)

  private def parametersOf(element: String): List[(String, String)] =
    val end = element.indexOf('>')
    if end < 0 then Nil
    else element.substring(end + 1).split(';').toList.flatMap(part => parameterOf(part).toList)

  private def parameterOf(part: String): Option[(String, String)] =
    val separator = part.indexOf('=')
    if separator < 0 then None
    else Some((part.take(separator).trim.toLowerCase(Locale.ROOT), part.drop(separator + 1).trim))

  private def unquote(value: String): String =
    if value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") then value.substring(1, value.length - 1)
    else value
