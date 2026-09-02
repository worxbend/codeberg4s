package com.worxbend.codeberg4s

import java.util.Locale

/** A closed set whose members each know the exact word Forgejo puts on the wire for them.
  *
  * Several of this library's enums are round-trippable: the same value is both decoded from a JSON field and written
  * back into a request. Before this trait existed each of them spelled that out twice — once as a `parse` matching
  * wire words to cases, once as a renderer matching cases back to wire words — and the two lists could drift apart
  * without the compiler noticing. Worse, the renderer was not even called the same thing everywhere: most enums named
  * it `wireName` and one named it `wireValue`.
  *
  * Extending this trait moves the wire word onto the case itself, so there is exactly one place where "this case is
  * spelled `dir`" is written down:
  *
  * ```scala
  * enum ContentKind(val wireName: String) extends WireVocabulary:
  *   case File      extends ContentKind("file")
  *   case Directory extends ContentKind("dir")
  *
  * object ContentKind:
  *   def parse(value: String): Option[ContentKind] = WireVocabulary.parse(values, value)
  * ```
  *
  * Only the enums that genuinely render '''and''' parse extend it. An enum this library merely decodes has no second
  * spelling to drift from, and giving it a renderer would mean inventing a word the API never asks for.
  */
trait WireVocabulary:

  /** The exact spelling Forgejo uses on the wire for this value. */
  def wireName: String

object WireVocabulary:

  /** Finds the member of `values` whose [[WireVocabulary.wireName]] matches `value`, or `None` if there is no match.
    *
    * The incoming string is trimmed and lowercased before the comparison, so a field that arrives as `" Public "`
    * still resolves. Lowercasing uses `Locale.ROOT` rather than the JVM's default locale: under a Turkish default
    * locale `"PRIVATE".toLowerCase` yields `"prıvate"` with a dotless `ı`, which would match nothing and turn a
    * perfectly good response into a silent `None` on one machine and not another.
    *
    * Answering `None` rather than failing is deliberate for every enum that uses this: a value Forgejo adds later must
    * cost the caller one field, not the whole payload it arrived in. Callers that cannot proceed without the value —
    * [[com.worxbend.codeberg4s.repositories.ContentKind]] is the example — turn that `None` into a decoding failure at
    * their own boundary.
    *
    * @param values
    *   every member of the enum, which is what its generated `values` gives you
    * @param value
    *   the raw string read off the wire
    */
  def parse[A <: WireVocabulary](values: Array[A], value: String): Option[A] =
    val normalized = value.trim.toLowerCase(Locale.ROOT)
    values.find(_.wireName.contentEquals(normalized))
