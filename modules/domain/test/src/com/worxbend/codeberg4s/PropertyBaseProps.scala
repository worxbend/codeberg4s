package com.worxbend.codeberg4s

import munit.ScalaCheckSuite
import munit.Tag
import org.scalacheck.Gen
import org.scalacheck.Test
import org.scalacheck.rng.Seed

/** Shared wiring for the `domain` module's ScalaCheck suites.
  *
  * '''The tag.''' Every property in this module carries [[Property]], whose value is the exact string `"Property"`.
  * That spelling is load-bearing: `verify.sh` runs the unit gate with `--exclude-tags=Property`, and munit's tag filter
  * compares against the tag's value, so a tag spelled anything else leaves the property suites inside the routine run —
  * which is precisely what `docs/CONSTITUTION_MAPPING.md` says must not happen. Nothing enforces the tag mechanically:
  * a property written without `.tag(Property)` silently rejoins the default gate.
  *
  * '''The seed.''' `scalaCheckInitialSeed` is pinned, so every run explores the same values in the same order.
  * ScalaCheck's own default seeds from the system clock, which turns a genuine counterexample into a flake that the
  * next run cannot reproduce, and this project's constitution wants property runs comparable build-to-build.
  */
trait PropertyBase extends ScalaCheckSuite:

  /** The tag excluded by `verify.sh`. Put it on every property in this module. */
  protected val Property: Tag = Tag(PropertyBase.TagName)

  override def scalaCheckInitialSeed: String = Seed(PropertyBase.SeedValue).toBase64

  /** munit's own default is ten successful cases per property, which is too few to reach the interesting corners of the
    * generators below — the traversal segments and control characters are a minority of what `hostileText` produces.
    */
  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(PropertyBase.MinSuccessfulTests)

/** Generators shared by this module's property suites.
  *
  * Declaration order matters here — these are `val`s in an object, so a generator that refers to one declared below it
  * would read `null` during initialisation.
  */
object PropertyBase:

  /** The value `verify.sh` passes to `--exclude-tags`. */
  val TagName: String = "Property"

  /** The pinned ScalaCheck seed. Change it to explore a different slice of the input space, never per run. */
  val SeedValue: Long = 20260801L

  /** Successful cases required before a property is believed. */
  val MinSuccessfulTests: Int = 300

  /** Characters an identifier may legitimately be built from.
    *
    * Deliberately excludes `.`, `/` and `,`, so a value assembled from these is acceptable to '''every''' string
    * identifier in the module at once: the single-segment types reject `/`, the segmented ones reject a `.` or `..`
    * segment, and the filter tokens reject `,`. One generator can therefore drive one round-trip property across all of
    * them.
    */
  private val SegmentCharacters: Vector[Char] =
    (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ Vector('-', '_', '~', 'é', 'ß')).toVector

  /** Inputs that a naive path-segment validator lets through. Mixed into [[hostileText]] so the shrinker has named
    * counterexamples to land on rather than only random noise.
    */
  private val KnownAttacks: Vector[String] = Vector(
    "",
    "   ",
    ".",
    "..",
    "../..",
    "a/../b",
    "/etc/passwd",
    "a//b",
    "a/",
    "/a",
    "name\r\nX-Injected: 1",
    "%2e%2e%2f",
    s"repo${0.toChar}name",
    "owner/repo",
    ".git",
    "a b",
    "\tleading-tab",
    "trailing-tab\t",
  )

  /** One character of a legitimate identifier. */
  val segmentCharacter: Gen[Char] = Gen.oneOf(SegmentCharacters)

  /** A non-empty value with no leading or trailing whitespace and no character any identifier in the module rejects.
    *
    * Interior spaces are allowed on purpose: `trim` must not touch them, and a constructor that stripped them would
    * silently rewrite a caller's value.
    */
  val plainSegment: Gen[String] =
    Gen
      .nonEmptyListOf(Gen.frequency(8 -> segmentCharacter, 1 -> Gen.const(' ')))
      .map(_.mkString.trim)
      .suchThat(_.nonEmpty)

  /** Surrounding whitespace of the kind a value picks up from an environment variable or a file. Possibly empty. */
  val padding: Gen[String] = Gen.listOf(Gen.oneOf(' ', '\t', '\n', '\r')).map(_.mkString)

  /** A value made of nothing but whitespace, including the empty string. */
  val blank: Gen[String] = padding

  /** NUL, tab, newline, carriage return, escape, delete and NEL — the characters `Char.isControl` answers `true` for,
    * and that a request line must never carry.
    */
  private val ControlCharacters: Vector[Char] = Vector(0, 9, 10, 13, 27, 127, 133).map(_.toChar)

  /** A character `Char.isControl` answers `true` for. */
  val controlCharacter: Gen[Char] = Gen.oneOf(ControlCharacters)

  private val hostileCharacter: Gen[Char] =
    Gen.frequency(
      6 -> segmentCharacter,
      3 -> Gen.oneOf('/', '\\', '.', ':', '%', '?', '#', '&', '=', ',', ' ', '@', '+'),
      2 -> Gen.oneOf(ControlCharacters),
    )

  /** Arbitrary text, weighted towards the shapes a path-forging or header-splitting attempt actually takes. */
  val hostileText: Gen[String] =
    Gen.frequency(
      3 -> Gen.listOf(hostileCharacter).map(_.mkString),
      1 -> Gen.oneOf(KnownAttacks),
    )

  /** Twelve uppercase letters.
    *
    * Every validation message in the module is lowercase ASCII prose, so a marker of this shape cannot appear in one by
    * coincidence. That is what makes "the rejection never echoes its input" checkable rather than a guess.
    */
  val marker: Gen[String] = Gen.listOfN(12, Gen.oneOf('A' to 'Z')).map(_.mkString)

  /** Lowercase hexadecimal digits, the alphabet both `CommitSha` and `LabelColor` normalise to. */
  val hexDigit: Gen[Char] = Gen.oneOf("0123456789abcdef".toVector)
