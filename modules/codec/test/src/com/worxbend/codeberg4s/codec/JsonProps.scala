package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.core.DecodeFailure

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.Prop.forAll
import org.scalacheck.Prop.forAllNoShrink

/** The one promise `Json` makes: decoding is total.
  *
  * `Json.decode` is the single door between a response body and a wire DTO, and ADR-0003 turns on it never letting an a
  * `JsonReaderException` escape into a caller's `Future`. That is a claim about '''every''' body a Forgejo instance or
  * a proxy in front of one could send — a truncated payload, a `text/plain` gateway error, a `null`, an empty string —
  * and it is therefore exactly the claim an example test cannot establish and a property test can.
  *
  * The generator feeds it random JSON punctuation, bodies observed in the wild, and truncations of well-formed
  * documents at every cut point, and the properties assert two things at once: that nothing escapes, and that the
  * failure value which comes back is usable — a non-empty reason, bounded at `Json.MaxReasonLength`, and a path that
  * renders as a JSONPath rooted at `$`. A decoder that answered with an unbounded message would turn a bug report into
  * a payload dump, which is the failure mode the bound exists for.
  */
final class JsonProps extends PropertyBase:

  /** What every failure `Json.decode` produces has to look like, whatever it was given. */
  private def isUsableFailure(failure: DecodeFailure): Prop =
    Prop.propBoolean(failure.message.trim.nonEmpty).label("a failure has to say something") &&
    Prop
      .propBoolean(failure.message.length <= Json.MaxReasonLength + 3)
      .label(s"the reason must stay bounded, was ${failure.message.length} characters") &&
    Prop
      .propBoolean(failure.path.render.startsWith("$"))
      .label(s"the path must be a JSONPath, was '${failure.path.render}'")

  private def decodesOrFailsCleanly[A: JsonDecoder](raw: String): Prop =
    Json.decode[A](raw) match
      case Right(_)      => Prop.passed
      case Left(failure) => isUsableFailure(failure)

  property("decoding an arbitrary body yields a usable failure value, never an exception".tag(Property)):
    forAll(PropertyBase.body) { raw =>
      decodesOrFailsCleanly[JsonValue](raw).label(s"as a JSON value: '$raw'") &&
      decodesOrFailsCleanly[Map[String, JsonValue]](raw).label(s"as an object: '$raw'") &&
      decodesOrFailsCleanly[Long](raw).label(s"as a number: '$raw'") &&
      decodesOrFailsCleanly[Vector[String]](raw).label(s"as an array of strings: '$raw'")
    }

  property("a document Json.render wrote decodes back to the value it was written from".tag(Property)):
    forAll(PropertyBase.value)(document => Json.decode[JsonValue](Json.render(document)) ?= Right(document))

  property("surrounding whitespace does not change what a document decodes to".tag(Property)):
    forAll(PropertyBase.value, PropertyBase.whitespace, PropertyBase.whitespace) { (document, before, after) =>
      val written = Json.render(document)

      Json.decode[JsonValue](s"$before$written$after") ?= Json.decode[JsonValue](written)
    }

  property("no proper prefix of an object or array document decodes".tag(Property)):
    forAll(PropertyBase.container, Gen.choose(0, 1000)) { (document, offset) =>
      val written = Json.render(document)
      val prefix  = written.take(offset % written.length)

      Prop
        .propBoolean(Json.decode[JsonValue](prefix).isLeft)
        .label(s"'$prefix' is an incomplete '$written' and must not decode")
    }

  property("the bare literal null is a failure, not a null reference".tag(Property)):
    forAll(PropertyBase.whitespace, PropertyBase.whitespace) { (before, after) =>
      val raw = s"${before}null$after"

      Prop
        .propBoolean(Json.decode[Map[String, JsonValue]](raw).isLeft)
        .label(s"'$raw' decoded into something for a type that cannot represent absence") &&
      (Json.decode[JsonValue](raw) ?= Right(JsonValue.Null))
        .label("a type that models absence itself is unaffected")
    }

  property("a decoder's explanation never becomes a payload dump".tag(Property)):
    forAllNoShrink(PropertyBase.longStringBody) { raw =>
      Json.decode[Long](raw) match
        case Right(number) => Prop.falsified.label(s"a ${raw.length}-character string decoded into $number")
        case Left(failure) =>
          Prop
            .propBoolean(raw.length > Json.MaxReasonLength)
            .label("the body has to be longer than the bound for this to mean anything") &&
          isUsableFailure(failure)
    }

  property("a document of the wrong shape fails rather than decoding into nonsense".tag(Property)):
    forAll(PropertyBase.container) { document =>
      Json.decode[Long](Json.render(document)) match
        case Left(failure) => isUsableFailure(failure)
        case Right(number) => Prop.falsified.label(s"'${Json.render(document)}' decoded into the number $number")
    }
