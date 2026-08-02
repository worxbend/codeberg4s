package com.worxbend.codeberg4s.codec

import munit.ScalaCheckSuite
import munit.Tag
import org.scalacheck.Gen
import org.scalacheck.Test
import org.scalacheck.rng.Seed

/** Shared wiring for the `codec` module's ScalaCheck suites.
  *
  * '''The tag.''' Every property in this module carries [[Property]], whose value is the exact string `"Property"`.
  * `verify.sh` runs the unit gate with `--exclude-tags=Property` and munit's tag filter compares against the tag's
  * value, so any other spelling silently leaves these suites inside the routine run. Nothing enforces this
  * mechanically: a property written without `.tag(Property)` rejoins the default gate.
  *
  * '''The seed.''' Pinned, so a counterexample found on one machine is reproducible on the next. Test modules do not
  * share code, so this trait is a near-copy of the ones in `domain` and `core`; that is the build's structure rather
  * than an oversight.
  */
trait PropertyBase extends ScalaCheckSuite:

  /** The tag excluded by `verify.sh`. Put it on every property in this module. */
  protected val Property: Tag = Tag(PropertyBase.TagName)

  override def scalaCheckInitialSeed: String = Seed(PropertyBase.SeedValue).toBase64

  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(PropertyBase.MinSuccessfulTests)

/** Generators shared by this module's property suites.
  *
  * Declaration order matters — these are `val`s in an object, so a generator referring to one declared below it would
  * read `null` during initialisation.
  */
object PropertyBase:

  /** The value `verify.sh` passes to `--exclude-tags`. */
  val TagName: String = "Property"

  /** The pinned ScalaCheck seed. */
  val SeedValue: Long = 20260801L

  /** Successful cases required before a property is believed. */
  val MinSuccessfulTests: Int = 300

  /** How deeply generated documents nest. Three levels is enough to exercise the tracing visitor's path recovery
    * without producing documents too large to print in a counterexample.
    */
  private val MaxDepth: Int = 3

  /** Bodies a Forgejo instance really has been seen to send, or that a proxy in front of one might.
    *
    * `404 page not found` is not an invention: the golden-fixture manifest records `GET /nodeinfo` answering with that
    * bare text rather than JSON, which is why every decoder in this module has to be total over non-JSON input.
    */
  private val KnownBodies: Vector[String] = Vector(
    "",
    " ",
    "null",
    "undefined",
    "404 page not found",
    "<html><body>502 Bad Gateway</body></html>",
    "{",
    "[",
    "{}",
    "[]",
    "{\"message\":",
    "\"unterminated",
    "{'message':'single quoted'}",
    "{\"message\":\"ok\"}{\"message\":\"twice\"}",
    "NaN",
    "0x10",
  )

  /** Characters that are load-bearing in JSON, so a generated body exercises the parser's error paths rather than
    * merely failing at the first character.
    */
  private val JsonPunctuation: Vector[Char] =
    Vector('{', '}', '[', ']', '"', ':', ',', '\\', '/', '.', '-', '+', 'e', 'E', ' ', '\n')

  /** Text that may appear inside a JSON string, including the characters that have to be escaped on the way out. */
  val text: Gen[String] =
    Gen
      .listOf(Gen.oneOf('a', 'b', 'z', '0', '9', '-', '_', ' ', '"', '\\', '/', '\n', '\t', 'é', '中'))
      .map(_.mkString)

  /** Text that survives `JsonFields.text`, which folds Forgejo's `""`-for-absent convention into `None`. */
  val nonBlankText: Gen[String] = text.suchThat(_.trim.nonEmpty)

  /** A JSON object key. Distinct keys are enforced where it matters, since a duplicate key is not round-trippable. */
  val key: Gen[String] = Gen.nonEmptyListOf(Gen.oneOf(('a' to 'z') ++ ('0' to '9'))).map(_.mkString)

  /** A JSON value that is not a container. */
  val scalar: Gen[JsonValue] =
    Gen.oneOf(
      Gen.const[JsonValue](JsonValue.Null),
      Gen.oneOf(true, false).map(flag        => JsonValue.Bool(flag)),
      Gen.choose(-100000, 100000).map(number => JsonValue.Num(number.toDouble)),
      text.map(value                         => JsonValue.Str(value)),
    )

  private def valueOfDepth(depth: Int): Gen[JsonValue] =
    if depth <= 0 then scalar
    else
      Gen.frequency(
        4 -> scalar,
        1 -> arrayOfDepth(depth),
        1 -> objectOfDepth(depth),
      )

  private def arrayOfDepth(depth: Int): Gen[JsonValue] =
    Gen
      .choose(0, 3)
      .flatMap(count => Gen.listOfN(count, valueOfDepth(depth - 1)))
      .map(items => JsonValue.Arr.from(items))

  private def objectOfDepth(depth: Int): Gen[JsonValue] =
    Gen
      .choose(0, 3)
      .flatMap(count => Gen.listOfN(count, key.flatMap(name => valueOfDepth(depth - 1).map(value => (name, value)))))
      .map(entries => JsonValue.Obj.from(entries.distinctBy((name, _) => name)))

  /** Any JSON document, containers included. */
  val value: Gen[JsonValue] = valueOfDepth(MaxDepth)

  /** A JSON document that is an object or an array, so every proper prefix of it is necessarily incomplete. */
  val container: Gen[JsonValue] =
    Gen.oneOf(arrayOfDepth(MaxDepth), objectOfDepth(MaxDepth))

  /** Arbitrary response bodies: random JSON punctuation, bodies seen in the wild, and truncations of well-formed
    * documents — which is the shape a connection dropped mid-response produces.
    */
  val body: Gen[String] =
    Gen.frequency(
      3 -> Gen.listOf(Gen.oneOf(JsonPunctuation)).map(_.mkString),
      2 -> truncated,
      1 -> Gen.oneOf(KnownBodies),
      1 -> value.map(document => Json.render(document)),
    )

  private def truncated: Gen[String] =
    for
      document <- value
      written   = Json.render(document)
      cut      <- Gen.choose(0, written.length)
    yield written.take(cut)

  /** Whitespace JSON allows around a document. */
  val whitespace: Gen[String] = Gen.listOf(Gen.oneOf(' ', '\t', '\n', '\r')).map(_.mkString)

  /** A JSON string document far longer than `Json.MaxReasonLength`.
    *
    * Measured against a derived codec: decoding a JSON string into a type that is not a string reports the string's
    * '''content''' as the reason, so the failure message is the payload. That is what `Json.MaxReasonLength` is for,
    * and it is only observable with a body longer than the bound — a body [[body]] never reaches, since ScalaCheck's
    * size parameter keeps generated collections short.
    */
  val longStringBody: Gen[String] =
    Gen
      .choose(Json.MaxReasonLength * 2, Json.MaxReasonLength * 6)
      .flatMap(length => Gen.listOfN(length, Gen.oneOf('a' to 'z')))
      .map(characters => Json.render(JsonValue.Str(characters.mkString)))
