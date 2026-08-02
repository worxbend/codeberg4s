package com.worxbend.codeberg4s.core

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.Prop.forAll

import java.util.Locale

/** The redaction boundary, stated over every secret and every hostile path segment.
  *
  * Every URI that reaches a `CallContext` — and therefore every URI that can reach an error message, a telemetry
  * callback or an application's log file — is produced here. That makes `Redaction` a security boundary rather than a
  * formatting helper, and gives it three obligations worth checking exhaustively rather than by example.
  *
  *   - '''A credential must not survive.''' A value under a sensitive parameter name is replaced by the mask, whatever
  *     the case of the name, and the material must not appear anywhere in the result.
  *   - '''A segment must not forge a path.''' A path segment containing `/` has to be percent-encoded, so the number of
  *     separators in the rendered URI is fixed by the number of segments and cannot be increased by a caller's value.
  *   - '''Nothing may corrupt the line.''' Whatever goes in, only unreserved characters and the URI punctuation this
  *     object emits itself may come out, which is what makes a control character in a caller's argument harmless.
  *
  * Secrets are generated as 24 alphanumeric characters, long enough that they cannot occur by accident in a URI built
  * from short generated words, so `contains` is a sound leak detector rather than a source of false alarms.
  */
final class RedactionProps extends PropertyBase:

  /** The characters `Redaction.uri` is allowed to emit after the base URI: RFC 3986 unreserved, the separators it
    * writes itself, and the `*` of the mask.
    */
  private val Permitted: String =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~/%?=&*"

  private val baseUris: Gen[String] =
    PropertyBase.word.map(host => s"https://$host.example/api/v1")

  private val paths: Gen[List[String]] =
    Gen.choose(0, 4).flatMap(count => Gen.listOfN(count, PropertyBase.hostileText.suchThat(_.nonEmpty)))

  private val ordinaryQuery: Gen[List[(String, String)]] =
    Gen.choose(0, 3).flatMap(count => Gen.listOfN(count, PropertyBase.word.flatMap(n => PropertyBase.word.map((n, _)))))

  /** A parameter name this library has no reason to hide. */
  private val ordinaryNames: Gen[String] =
    PropertyBase.word.suchThat(name => !Redaction.SensitiveQueryParameters.contains(name))

  private val ordinaryHeaders: Gen[List[(String, String)]] =
    Gen.listOf(ordinaryNames.flatMap(name => PropertyBase.word.map(value => (name, value))))

  /** A sensitive parameter name in an arbitrary casing — the comparison is documented as case-insensitive. */
  private val sensitiveNames: Gen[String] =
    Gen
      .oneOf(Redaction.SensitiveQueryParameters.toVector)
      .flatMap(name => recased(name))

  private val sensitiveHeaders: Gen[String] =
    Gen
      .oneOf(Redaction.SensitiveHeaders.toVector)
      .flatMap(name => recased(name))

  private def recased(name: String): Gen[String] =
    Gen
      .listOfN(name.length, Gen.oneOf(true, false))
      .map(flags => name.zip(flags).map((char, upper) => if upper then char.toUpper else char).mkString)

  property("a credential under a sensitive parameter name never reaches the rendered URI".tag(Property)):
    forAll(baseUris, paths, ordinaryQuery, sensitiveNames, PropertyBase.secret) {
      (base, path, query, name, secret) =>
        val rendered = Redaction.uri(base, path, query.appended((name, secret)))

        Prop.propBoolean(!rendered.contains(secret)).label(s"the secret survived in: $rendered") &&
        Prop.propBoolean(rendered.contains(s"$name=${Redaction.Mask}")).label(s"no mask in: $rendered")
    }

  property("a value under an ordinary parameter name survives verbatim, never masked".tag(Property)):
    forAll(baseUris, ordinaryNames, PropertyBase.secret) { (base, name, value) =>
      val rendered = Redaction.uri(base, Nil, List((name, value)))

      Prop
        .propBoolean(rendered.contains(s"$name=$value"))
        .label(s"an unremarkable parameter must not be masked: $rendered")
    }

  property("a path segment cannot add a separator, however it is spelled".tag(Property)):
    forAll(baseUris, paths) { (base, path) =>
      val rendered  = Redaction.uri(base, path, Nil)
      val separator = '/'

      Prop.propBoolean(rendered.startsWith(base)).label(s"the base URI must be kept verbatim: $rendered") &&
      (rendered.drop(base.length).count(_.equals(separator)) ?= path.size)
        .label(s"${path.size} segments produced: ${rendered.drop(base.length)}")
    }

  property("nothing a caller supplies can put an unsafe character into a URI".tag(Property)):
    forAll(baseUris, paths, ordinaryQuery, sensitiveNames, PropertyBase.secret) {
      (base, path, query, name, secret) =>
        val rendered = Redaction.uri(base, path, query.appended((name, secret)))
        val emitted  = rendered.drop(base.length)
        val unsafe   = emitted.filterNot(char => Permitted.contains(char))

        Prop.propBoolean(unsafe.isEmpty).label(s"characters that must not appear: ${unsafe.map(_.toInt).mkString(",")}")
    }

  property("a sensitive header is masked in any casing, and every other header is untouched".tag(Property)):
    forAll(ordinaryHeaders, sensitiveHeaders, PropertyBase.secret) { (ordinary, sensitive, secret) =>
      val entries = ordinary.appended((sensitive, secret))
      val masked  = Redaction.headers(entries)

      val eachEntry = entries.zip(masked).forall {
        case ((name, value), (maskedName, maskedValue)) =>
          maskedName.equals(name) &&
          (if Redaction.SensitiveHeaders.contains(name.toLowerCase(Locale.ROOT)) then
             maskedValue.equals(Redaction.Mask)
           else maskedValue.equals(value))
      }

      (masked.size ?= entries.size).label("no header may be dropped") &&
      Prop.propBoolean(eachEntry).label(s"masked: $masked") &&
      Prop.propBoolean(!masked.exists((_, value) => value.contains(secret))).label("the credential must not survive")
    }
