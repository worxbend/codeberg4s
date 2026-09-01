package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.repositories.BranchName

import munit.FunSuite

/** What the compile-time identifier constructors promise.
  *
  * Two of these tests are ordinary assertions about accepted literals. The rest are the interesting ones: they use
  * munit's `compileErrors`, which compiles a snippet and hands back the compiler's message instead of failing the
  * build, so a literal that '''must not''' compile can be asserted on like any other value.
  *
  * The last two are the drift guard [[SegmentLiteral]]'s Scaladoc promises. The rule is written twice — once as
  * [[PathSegment]]'s `if`/`else` chain, once as a regular expression — and nothing in the compiler ties the two
  * spellings together. These walk a corpus of awkward values through both and demand the same verdict, so changing one
  * without the other fails here rather than leaving a value the run-time parser rejects and the compiler waves through.
  */
final class SegmentLiteralSuite extends FunSuite:

  test("a valid literal becomes the identifier with no Either to unwrap"):
    assertEquals(Owner("forgejo").value, "forgejo")
    assertEquals(RepoName("code.berg-4s_v1").value, "code.berg-4s_v1")

  test("a slashed literal is accepted where the identifier legitimately spans segments"):
    assertEquals(BranchName("v16.0/forgejo").value, "v16.0/forgejo")

  test("a literal containing a slash does not compile where one segment is required"):
    assert(compileErrors("""Owner("forgejo/forgejo")""").contains("not a valid owner"))

  test("a traversal literal does not compile"):
    assert(compileErrors("""Owner("..")""").contains("not a valid owner"))
    assert(compileErrors("""BranchName("release/../main")""").contains("not a valid branch"))

  test("an empty literal does not compile"):
    assert(compileErrors("""RepoName("")""").contains("not a valid repoName"))

  test("a literal with surrounding whitespace does not compile, rather than being trimmed"):
    assert(compileErrors("""Owner(" forgejo ")""").contains("not a valid owner"))

  test("a value known only at run time is sent to `from` instead"):
    val message = compileErrors("""val raw: String = "forgejo"; Owner(raw)""")
    assert(message.contains("string literal"), message)

  test("the compile-time rule for one segment agrees with PathSegment.from"):
    val plain = scala.compiletime.constValue[SegmentLiteral.Plain]
    Corpus.foreach: candidate =>
      assertEquals(
        candidate.matches(plain),
        PathSegment.from("field", candidate).isRight,
        s"disagreement on ${escape(candidate)}",
      )

  test("the compile-time rule for several segments agrees with PathSegment.segmented"):
    val segmented = scala.compiletime.constValue[SegmentLiteral.Segmented]
    Corpus.foreach: candidate =>
      assertEquals(
        candidate.matches(segmented),
        PathSegment.segmented("field", candidate).isRight,
        s"disagreement on ${escape(candidate)}",
      )

  /** Values chosen to sit on the edges of both spellings of the rule.
    *
    * Every entry is already equal to its own `trim`, because whitespace padding is the one place the two rules are
    * meant to disagree: `PathSegment` trims it away and the literal check refuses it outright, so a padded value would
    * report a difference that is intended rather than a drift.
    *
    * A control character is a different matter and belongs here: `U+0001` is not whitespace, so `trim` never removes
    * it, and both halves of the rule have to refuse it wherever it sits. `U+0085` earns its place too — it is a control
    * character to `Char.isControl` but not to a Java regex's `\p{Cntrl}`, which is why the patterns say `\p{Cc}`.
    */
  private val Corpus: List[String] = List(
    "forgejo",
    "a",
    "code.berg-4s_v1",
    "_CYBER_STONES_",
    "-_-",
    "...",
    ".hidden",
    "..hidden",
    "",
    ".",
    "..",
    "a/b",
    "v16.0/forgejo",
    "/main",
    "main/",
    "a//b",
    "a/../b",
    "a/./b",
    "a/..",
    "../b",
    "forge\njo",
    "forge\tjo",
    "a b",
    "ab",
    "\u0085ab",
    "ab\u0085",
    "\u0001forgejo",
    "forgejo\u0001",
    "a/\u0001b",
  )

  private def escape(candidate: String): String =
    candidate.flatMap(character => if character.isControl then f"\\u${character.toInt}%04x" else character.toString)
