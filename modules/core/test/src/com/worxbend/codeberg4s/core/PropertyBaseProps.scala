package com.worxbend.codeberg4s.core

import munit.ScalaCheckSuite
import munit.Tag
import org.scalacheck.Gen
import org.scalacheck.Test
import org.scalacheck.rng.Seed

/** Shared wiring for the `core` module's ScalaCheck suites.
  *
  * '''The tag.''' Every property in this module carries [[Property]], whose value is the exact string `"Property"`.
  * `verify.sh` runs the unit gate with `--exclude-tags=Property` and munit's tag filter compares against the tag's
  * value, so any other spelling silently leaves these suites inside the routine run. The tag is applied by
  * [[munitTests]] below rather than trusted to every author, so a property written without `.tag(Property)` is still
  * excluded; the explicit tags kept on the existing properties are redundant with that override, not load-bearing.
  *
  * '''The seed.''' Pinned, so a counterexample found on one machine is reproducible on the next. The module's test
  * modules do not share code, so this trait is a near-copy of the one in `domain`; that is the build's structure, not
  * an oversight.
  */
trait PropertyBase extends ScalaCheckSuite:

  /** The tag excluded by `verify.sh`. Put it on every property in this module. */
  protected val Property: Tag = Tag(PropertyBase.TagName)

  /** Tags every test declared in a subclass with [[Property]], whether or not its author remembered to.
    *
    * munit builds the suite's test list first and filters it by tag afterwards, so adding the tag here is equivalent to
    * writing `.tag(Property)` on each declaration — and unlike the convention, it cannot be forgotten. `tags` is a
    * `Set`, so re-tagging an already-tagged property changes nothing.
    */
  override def munitTests(): Seq[munit.Test] = super.munitTests().map(_.tag(Property))

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

  private val WordCharacters: Vector[Char] = (('a' to 'z') ++ ('0' to '9') ++ Vector('-', '_')).toVector

  private val ControlCharacters: Vector[Char] = Vector(0, 9, 10, 13, 27, 127, 133).map(_.toChar)

  /** Header and URI fragments that a naive parser mishandles. Mixed into [[hostileText]] so the shrinker has named
    * counterexamples to land on rather than only random noise.
    */
  private val KnownAwkwardHeaders: Vector[String] = Vector(
    "",
    ",",
    ";",
    "<>",
    "<",
    ">",
    "rel=next",
    "<https://codeberg.org/api/v1/x?page=2>; rel=\"next\"",
    "<https://codeberg.org/api/v1/x?page=2>; rel=next",
    "<https://codeberg.org/api/v1/x?page=2>; rel=\"next prev\"",
    "<https://codeberg.org/api/v1/x?a=1,2&page=3>; rel=\"last\"",
    "<>; rel=\"next\"",
    "<   >; rel=\"next\"",
    "; rel=\"next\"",
    "<https://codeberg.org/x>",
    "<https://codeberg.org/x>; rel=",
    "<https://codeberg.org/x>; rel=\"\"",
    "<a>; rel=\"NEXT\", <b>; rel=\"next\"",
  )

  /** A short, unremarkable word: the shape of a path segment or a query parameter name. */
  val word: Gen[String] = Gen.nonEmptyListOf(Gen.oneOf(WordCharacters)).map(_.mkString)

  /** A character `Char.isControl` answers `true` for. */
  val controlCharacter: Gen[Char] = Gen.oneOf(ControlCharacters)

  private val hostileCharacter: Gen[Char] =
    Gen.frequency(
      5 -> Gen.oneOf(WordCharacters),
      4 -> Gen.oneOf('<', '>', ';', ',', '=', '"', '/', ':', '?', '&', '%', '#', ' ', '\\'),
      1 -> Gen.oneOf(ControlCharacters),
    )

  /** Arbitrary text, weighted towards the punctuation that `Link` header and URI parsing has to survive. */
  val hostileText: Gen[String] =
    Gen.frequency(
      3 -> Gen.listOf(hostileCharacter).map(_.mkString),
      1 -> Gen.oneOf(KnownAwkwardHeaders),
    )

  /** Twenty-four alphanumeric characters.
    *
    * Long and random enough that it cannot appear by coincidence inside a rendering assembled from short fixed
    * literals, which is what makes `contains` a sound leak detector rather than a source of false alarms.
    */
  val secret: Gen[String] =
    Gen.listOfN(24, Gen.oneOf(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9'))).map(_.mkString)
