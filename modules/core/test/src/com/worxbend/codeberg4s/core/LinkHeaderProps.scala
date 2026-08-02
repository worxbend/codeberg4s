package com.worxbend.codeberg4s.core

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.Prop.forAll

import java.util.Locale

/** `Link` header parsing, which has to be total.
  *
  * The header is the only end-of-collection signal Forgejo emits that can be trusted, and it arrives through whatever
  * proxies sit in front of the instance. `LinkHeader` therefore promises never to fail: an unreadable header yields an
  * empty map and an unreadable element is skipped while its neighbours survive, because failing a listing over a header
  * a proxy rewrote would turn a cosmetic problem into an outage.
  *
  * "Never fails" is exactly the kind of claim a property test can check and an example test cannot. The first two
  * properties below throw arbitrary text at the parser — balanced and unbalanced brackets, stray commas and semicolons,
  * quotes, control characters — and assert both that nothing escapes and that every entry that does come back is well
  * formed. The rest state the readings the parser is supposed to have: a comma inside the angle-bracketed target does
  * not separate elements, relation types are case-insensitive, the first occurrence of a relation type wins, and one
  * unreadable element does not take its neighbours with it.
  */
final class LinkHeaderProps extends PropertyBase:

  /** Targets deliberately contain a comma: a parser that split elements on every comma would lose them. */
  private val targets: Gen[String] =
    for
      host <- PropertyBase.word
      page <- Gen.choose(1, 999)
      size <- Gen.choose(1, 50)
    yield s"https://codeberg.org/api/v1/$host?page=$page&limit=$size&labels=bug,help"

  private val relations: Gen[String] =
    Gen.oneOf("next", "prev", "previous", "first", "last", "NEXT", "Prev", "LAST")

  /** One `<target>; rel=…` element, quoted or not, as both spellings are legal and both are seen in the wild. */
  private def render(target: String, relation: String, quoted: Boolean): String =
    if quoted then s"""<$target>; rel="$relation"""" else s"<$target>; rel=$relation"

  private val links: Gen[Vector[(String, String)]] =
    Gen
      .choose(1, 4)
      .flatMap(count =>
        Gen.listOfN(count, relations.flatMap(relation => targets.map(target => (relation, target))))
      )
      .map(_.toVector)
      .map(pairs => pairs.distinctBy((relation, _) => relation.toLowerCase(Locale.ROOT)))

  private val quoting: Gen[Boolean] = Gen.oneOf(true, false)

  private def rendered(pairs: Vector[(String, String)], quoted: Boolean): String =
    pairs.map((relation, target) => render(target, relation, quoted)).mkString(", ")

  property("parsing arbitrary text never fails, and never yields a malformed entry".tag(Property)):
    forAll(PropertyBase.hostileText) { raw =>
      val parsed = LinkHeader.parse(raw)
      Prop
        .propBoolean(parsed.keys.forall(relation => relation.nonEmpty))
        .label("a relation type is never empty") &&
      Prop
        .propBoolean(parsed.keys.forall(relation => relation.toLowerCase(Locale.ROOT).equals(relation)))
        .label("relation types are lowercased, because RFC 8288 defines them case-insensitively") &&
      Prop
        .propBoolean(parsed.keys.forall(relation => !relation.exists(_.isWhitespace)))
        .label("a relation type never carries whitespace") &&
      Prop
        .propBoolean(parsed.values.forall(target => target.nonEmpty && target.trim.equals(target)))
        .label("a target is never empty and never padded")
    }

  property("reading a query parameter out of arbitrary text never fails and never invents a value".tag(Property)):
    forAll(PropertyBase.hostileText, PropertyBase.word) { (raw, name) =>
      LinkHeader.queryParameter(raw, name) match
        case None        => Prop.passed
        case Some(value) =>
          Prop.propBoolean(raw.contains(value)).label(s"'$value' is not part of '$raw'") &&
          Prop.propBoolean(!value.contains('&')).label(s"'$value' spans a parameter separator")
    }

  property("a well-formed header round-trips, whatever the case and the quoting".tag(Property)):
    forAll(links, quoting) { (pairs, quoted) =>
      val expected = pairs.map((relation, target) => (relation.toLowerCase(Locale.ROOT), target)).toMap

      (LinkHeader.parse(rendered(pairs, quoted)) ?= expected)
        .label(s"from: ${rendered(pairs, quoted)}")
    }

  property("the first occurrence of a relation type wins".tag(Property)):
    forAll(relations, targets, targets, quoting) { (relation, first, second, quoted) =>
      val header = s"${render(first, relation, quoted)}, ${render(second, relation, quoted)}"

      LinkHeader.parse(header).get(relation.toLowerCase(Locale.ROOT)) match
        case Some(target) => (target ?= first).label(s"from: $header")
        case None         => Prop.falsified.label(s"the relation type disappeared entirely: $header")
    }

  property("an unreadable element does not take its neighbours with it".tag(Property)):
    forAll(links, quoting, PropertyBase.word, Gen.oneOf(true, false)) { (pairs, quoted, junk, junkFirst) =>
      val good     = rendered(pairs, quoted)
      val header   = if junkFirst then s"$junk, $good" else s"$good, $junk"
      val expected = pairs.map((relation, target) => (relation.toLowerCase(Locale.ROOT), target)).toMap

      (LinkHeader.parse(header) ?= expected).label(s"from: $header")
    }

  property("the page number of a link target is read back exactly".tag(Property)):
    forAll(PropertyBase.word, Gen.choose(1, 99999)) { (host, page) =>
      val target = s"https://codeberg.org/api/v1/$host?limit=50&page=$page&state=open"

      (LinkHeader.queryParameter(target, "page") ?= Some(page.toString)).label("the page parameter") &&
      (LinkHeader.queryParameter(target, "PAGE") ?= Some(page.toString)).label("matched case-insensitively") &&
      (LinkHeader.queryParameter(target, "absent") ?= None).label("a parameter that is not there")
    }
