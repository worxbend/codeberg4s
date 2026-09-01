package com.worxbend.codeberg4s.auth

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.PropertyBase
import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.PageParams

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.Prop.forAll

/** The redaction guarantee, stated over every secret rather than over one example.
  *
  * `ApiToken` and `Password` exist for exactly one reason: a credential must not be able to reach a log line, an
  * exception message or a bug report by accident. Both types buy that guarantee with an overridden `toString` on a
  * final class — their Scaladoc explains why an `opaque type … = String` cannot, since outside its defining scope
  * `s"$token"` would dispatch to `String`'s own `toString` and print the secret.
  *
  * The central property below is the one that would catch that regression. It takes a generated secret, threads it
  * through every rendering path this library has — the wrapper itself, string interpolation, the enclosing `Auth` case,
  * a whole `CodebergConfig`, a collection holding one, and `CodebergError.describe` for an error built with the secret
  * interpolated into every free-form position an error has — and asserts that not one of those strings contains the
  * material. An example-based test can only check the paths someone thought of; this checks them for every secret, and
  * fails naming the path that leaked.
  *
  * Secrets are generated as 24 alphanumeric characters. That length matters: every other string in a rendering is a
  * short fixed literal, so a 24-character random value cannot appear in one by coincidence, and `contains` is therefore
  * a sound leak detector rather than a source of false alarms.
  */
final class SecretProps extends PropertyBase:

  private val secrets: Gen[String] =
    Gen.listOfN(24, Gen.oneOf(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9'))).map(_.mkString)

  private def tokenOf(secret: String): ApiToken =
    ApiToken.from(secret) match
      case Right(token) => token
      case Left(error)  => fail(s"the generator produced an unusable token: ${error.message}")

  private def passwordOf(secret: String): Password =
    Password.from(secret) match
      case Right(password) => password
      case Left(error)     => fail(s"the generator produced an unusable password: ${error.message}")

  /** A call context with the token interpolated into every field that carries free text.
    *
    * This is the accident the type is defending against: someone builds a context out of a configuration value without
    * thinking about it. Interpolation must render the mask, so the context is harmless.
    */
  private def contextAround(token: ApiToken): CallContext =
    CallContext(
      operation  = s"issues.list-$token",
      method     = HttpMethod.Get,
      uri        = s"https://codeberg.org/api/v1/repos/owner/name/issues?token=$token",
      requestId  = Some(s"$token"),
      durationMs = 12L,
    )

  /** Every case of the error ADT, each built with the token interpolated into every free-form position it has. */
  private def errorsAround(token: ApiToken): Vector[(String, CodebergError)] =
    val context        = contextAround(token)
    Vector(
      "Transport"        -> CodebergError.Transport(context, TransportCause.Unknown(s"$token")),
      "Api"              -> CodebergError.Api(
        context,
        401,
        ApiErrorBody(Some(s"$token"), Some(s"$token"), List(s"$token")),
        None,
      ),
      "DecodingFailed"   -> CodebergError.DecodingFailed(
        context,
        s"$token",
        JsonPath.of("auth", "token"),
        s"$token",
      ),
      "Validation"       -> ValidationError("apiToken", s"$token"),
      "RetriesExhausted" -> CodebergError.RetriesExhausted(
        context,
        3,
        CodebergError.Transport(context, TransportCause.Timeout(s"$token")),
      ),
      // The one case with no free-form position and no CallContext: its
      // rendering is built from two numbers. Listed anyway, so that the claim
      // "every case of the error ADT" stays a claim about the whole ADT and a
      // later case that does carry text is added beside a neighbour rather
      // than into a gap nobody notices.
      "WalkTruncated"    -> CodebergError.WalkTruncated(3, PageParams.First),
    )

  /** Every way this library can turn a credential into text, named so a failure says which path leaked. */
  private def renderings(secret: String): Vector[(String, String)] =
    val token    = tokenOf(secret)
    val password = passwordOf(secret)
    Vector(
      "ApiToken.toString"     -> token.toString,
      "ApiToken interpolated" -> s"$token",
      "ApiToken.redacted"     -> token.redacted,
      "Option[ApiToken]"      -> Option(token).toString,
      "List[ApiToken]"        -> List(token).toString,
      "Auth.Token"            -> Auth.Token(token).toString,
      "CodebergConfig(token)" -> CodebergConfig(Auth.Token(token)).toString,
      "Password.toString"     -> password.toString,
      "Password interpolated" -> s"$password",
      "Password.redacted"     -> password.redacted,
      "Auth.Basic"            -> Auth.Basic("someone", password).toString,
      "CodebergConfig(basic)" -> CodebergConfig(Auth.Basic("someone", password)).toString,
      "Map[String, Password]" -> Map("password" -> password).toString,
    ) ++ errorsAround(token).map((name, error) => s"CodebergError.$name.describe" -> error.describe)

  property("no rendering path this library has can emit the secret it was given".tag(Property)):
    forAll(secrets) { secret =>
      val leaked = renderings(secret).filter((_, rendered) => rendered.contains(secret))
      Prop
        .propBoolean(leaked.isEmpty)
        .label(s"the secret escaped through: ${leaked.map((name, _) => name).mkString(", ")}")
    }

  property("a credential renders as the mask and nothing else".tag(Property)):
    forAll(secrets) { secret =>
      (tokenOf(secret).toString ?= ApiToken.Redacted) &&
      (s"${tokenOf(secret)}" ?= ApiToken.Redacted) &&
      (passwordOf(secret).toString ?= Password.Redacted) &&
      (s"${passwordOf(secret)}" ?= Password.Redacted)
    }

  property("reveal is the only way to see the material".tag(Property)):
    forAll(secrets)(secret => (tokenOf(secret).reveal ?= secret) && (passwordOf(secret).reveal ?= secret))

  property("a token is accepted exactly when trimming leaves something free of control characters".tag(Property)):
    forAll(PropertyBase.hostileText) { raw =>
      val trimmed = raw.trim
      (ApiToken.from(raw).isRight ?= (trimmed.nonEmpty && !trimmed.exists(_.isControl))).label(s"from '$raw'")
    }

  property("an accepted token reveals exactly the trimmed input".tag(Property)):
    forAll(PropertyBase.hostileText) { raw =>
      ApiToken.from(raw) match
        case Left(_)      => Prop.passed
        case Right(token) => (token.reveal ?= raw.trim).label(s"from '$raw'")
    }

  property("a password is accepted exactly when it is non-empty and free of control characters".tag(Property)):
    forAll(PropertyBase.hostileText) { raw =>
      (Password.from(raw).isRight ?= (raw.nonEmpty && !raw.exists(_.isControl))).label(s"from '$raw'")
    }

  property("a password keeps the surrounding whitespace a token would have trimmed".tag(Property)):
    forAll(secrets, Gen.choose(0, 4), Gen.choose(0, 4)) { (secret, before, after) =>
      val padded = s"${" ".repeat(before)}$secret${" ".repeat(after)}"
      (Password.from(padded).map(_.reveal) ?= Right(padded)) &&
      (ApiToken.from(padded).map(_.reveal) ?= Right(secret))
    }

  property("a rejected secret is never echoed in the message that rejected it".tag(Property)):
    forAll(secrets, PropertyBase.controlCharacter) { (secret, control) =>
      val hostile  = s"$secret${control}trailing"
      val messages = Vector(
        ApiToken.from(hostile).swap.toOption.map("apiToken" -> _),
        Password.from(hostile).swap.toOption.map("password" -> _),
      ).flatten
      (messages.size ?= 2).label("a control character in the middle must be rejected by both types") &&
      Prop
        .propBoolean(messages.forall((_, error) => !error.message.contains(secret)))
        .label(s"messages were: ${messages.map((_, error) => error.message).mkString("; ")}")
    }

  property("credentials compare on their material and never across the two types".tag(Property)):
    forAll(secrets, secrets, Gen.oneOf(true, false)) { (first, other, same) =>
      val second = if same then first else other
      (tokenOf(first).equals(tokenOf(second)) ?= first.equals(second)) &&
      (passwordOf(first).equals(passwordOf(second)) ?= first.equals(second)) &&
      Prop.propBoolean(!tokenOf(first).equals(passwordOf(first))).label("a token must never equal a password")
    }
