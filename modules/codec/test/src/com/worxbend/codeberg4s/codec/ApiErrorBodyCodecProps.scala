package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.ApiErrorBody

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.Prop.forAll

/** `ApiErrorBodyCodec.parse` has to be total, because it runs after something has already failed.
  *
  * An error body is the explanation of a non-2xx response. A second failure while reading it would mask the status code
  * that carried it, so the codec's contract is that a body which is not JSON, is not an object, is empty or is
  * truncated becomes `ApiErrorBody.Empty` rather than an error. `docs/HAZARDS.md` records that this path is exercised
  * in practice rather than in theory: Forgejo answers some requests with `text/plain`.
  *
  * The properties below check totality against arbitrary bodies, and then check that the leniency is exactly the
  * documented one rather than something broader — a field of the wrong JSON kind is '''absence''', a blank string is
  * absence, non-string elements of `errors` are dropped while the rest keep their order, and a payload that is well
  * formed is read back with nothing lost. The last of those is what stops "be lenient" from quietly becoming "throw
  * information away".
  */
final class ApiErrorBodyCodecProps extends PropertyBase:

  private val notAString: Gen[ujson.Value] =
    Gen.oneOf(
      Gen.const[ujson.Value](ujson.Null),
      Gen.oneOf(true, false).map(flag    => ujson.Bool(flag)),
      Gen.choose(-1000, 1000).map(number => ujson.Num(number.toDouble)),
      Gen.const[ujson.Value](ujson.Arr.from(List(ujson.Str("nested")))),
      Gen.const[ujson.Value](ujson.Obj.from(List(("nested", ujson.Str("value"): ujson.Value)))),
    )

  private val notAnArray: Gen[ujson.Value] =
    Gen.oneOf(
      Gen.const[ujson.Value](ujson.Null),
      Gen.oneOf(true, false).map(flag    => ujson.Bool(flag)),
      Gen.choose(-1000, 1000).map(number => ujson.Num(number.toDouble)),
      PropertyBase.text.map(value        => ujson.Str(value)),
      Gen.const[ujson.Value](ujson.Obj.from(List(("nested", ujson.Str("value"): ujson.Value)))),
    )

  private val extraFields: Gen[List[(String, ujson.Value)]] =
    Gen
      .choose(0, 3)
      .flatMap(count => Gen.listOfN(count, PropertyBase.key.flatMap(name => PropertyBase.scalar.map(v => (name, v)))))
      .map(entries => entries.filterNot((name, _) => Set("message", "url", "errors").contains(name)))

  private def objectOf(entries: List[(String, ujson.Value)]): String =
    ujson.write(ujson.Obj.from(entries.distinctBy((name, _) => name)))

  property("parsing an arbitrary body always answers, and only an object can say anything".tag(Property)):
    forAll(PropertyBase.body) { raw =>
      val parsed   = ApiErrorBodyCodec.parse(raw)
      val isObject = Json.decode[ujson.Value](raw).exists(document => document.objOpt.isDefined)

      Prop
        .propBoolean(isObject || parsed.equals(ApiErrorBody.Empty))
        .label(s"'$raw' is not a JSON object but produced $parsed") &&
      Prop.propBoolean(parsed.message.forall(_.trim.nonEmpty)).label("a blank message is absence") &&
      Prop.propBoolean(parsed.url.forall(_.trim.nonEmpty)).label("a blank url is absence")
    }

  property("a well-formed payload is read back with nothing lost and nothing invented".tag(Property)):
    forAll(
      Gen.option(PropertyBase.nonBlankText),
      Gen.option(PropertyBase.nonBlankText),
      Gen.listOf(PropertyBase.text),
      extraFields,
    ) { (message, url, errors, extras) =>
      val entries =
        message.map(value => ("message", ujson.Str(value): ujson.Value)).toList ++
          url.map(value => ("url", ujson.Str(value): ujson.Value)).toList ++
          List(("errors", ujson.Arr.from(errors.map(entry => ujson.Str(entry))): ujson.Value)) ++
          extras

      (ApiErrorBodyCodec.parse(objectOf(entries)) ?= ApiErrorBody(message, url, errors))
        .label(s"from ${objectOf(entries)}")
    }

  property("a field of the wrong JSON kind is absence, never a failure".tag(Property)):
    forAll(notAString, notAString, notAnArray) { (message, url, errors) =>
      val body = objectOf(List(("message", message), ("url", url), ("errors", errors)))

      (ApiErrorBodyCodec.parse(body) ?= ApiErrorBody.Empty).label(s"from $body")
    }

  property("a blank message is folded away, matching Forgejo's empty-string-for-absent convention".tag(Property)):
    forAll(PropertyBase.whitespace) { padding =>
      val body = objectOf(List(("message", ujson.Str(padding)), ("url", ujson.Str(padding))))

      (ApiErrorBodyCodec.parse(body) ?= ApiErrorBody.Empty).label(s"from $body")
    }

  property("elements of errors that are not strings are dropped, and the rest keep their order".tag(Property)):
    forAll(Gen.listOf(Gen.frequency(2 -> PropertyBase.text.map(t => ujson.Str(t): ujson.Value), 1 -> notAString))) {
      elements =>
        val body     = objectOf(List(("errors", ujson.Arr.from(elements))))
        val expected = elements.flatMap(element => element.strOpt)

        (ApiErrorBodyCodec.parse(body).errors ?= expected).label(s"from $body")
    }
