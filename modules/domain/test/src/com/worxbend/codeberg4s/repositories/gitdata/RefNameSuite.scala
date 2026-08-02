package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** [[RefName]] and [[CompareRange]] — the two path parameters in this group that legitimately contain a slash.
  *
  * Both are security boundaries rather than conveniences: the value they hold is decomposed into URI path segments, so
  * a `..` that survived construction would climb out of the route it was built for.
  */
final class RefNameSuite extends FunSuite:

  test("accepts a fully qualified ref"):
    assertEquals(RefName.from("refs/heads/main").toOption.map(_.value), Some("refs/heads/main"))

  test("accepts a partial ref, which is what the prefix listing takes"):
    assertEquals(RefName.from("heads").toOption.map(_.value), Some("heads"))

  test("splits on slashes, because Forgejo routes a ref with a wildcard"):
    assertEquals(RefName.from("refs/heads/main").toOption.map(_.segments), Some(List("refs", "heads", "main")))

  test("a ref with no slash is one segment"):
    assertEquals(RefName.from("main").toOption.map(_.segments), Some(List("main")))

  test("trims surrounding whitespace"):
    assertEquals(RefName.from("  refs/tags/v1  ").toOption.map(_.value), Some("refs/tags/v1"))

  test("rejects a blank ref"):
    assertEquals(field(RefName.from("   ")), Some("ref"))

  test("rejects a '..' segment, which percent-encoding would not neutralise"):
    assertEquals(field(RefName.from("refs/../../admin")), Some("ref"))

  test("rejects a '.' segment"):
    assertEquals(field(RefName.from("refs/./heads")), Some("ref"))

  test("rejects a leading slash"):
    assertEquals(field(RefName.from("/refs/heads/main")), Some("ref"))

  test("rejects a trailing slash"):
    assertEquals(field(RefName.from("refs/heads/")), Some("ref"))

  test("rejects an empty middle segment"):
    assertEquals(field(RefName.from("refs//heads")), Some("ref"))

  test("rejects an embedded control character"):
    assertEquals(field(RefName.from("refs/he\nads")), Some("ref"))

  test("a range built from two refs is joined with three dots"):
    assertEquals(range("v1.0", "main").value, "v1.0...main")

  test("a range over slashed refs splits at the slashes and not at the separator"):
    assertEquals(range("main", "renovate/deps").segments, List("main...renovate", "deps"))

  test("a range over unslashed refs is a single segment, dots and all"):
    assertEquals(range("v1.0", "v2.0").segments, List("v1.0...v2.0"))

  test("a range parsed from one string keeps it verbatim"):
    assertEquals(CompareRange.from("v1...v2").toOption.map(_.value), Some("v1...v2"))

  test("a parsed range trims surrounding whitespace"):
    assertEquals(CompareRange.from("  v1...v2  ").toOption.map(_.value), Some("v1...v2"))

  test("a value with no separator is not a comparison"):
    assertEquals(field(CompareRange.from("main")), Some("basehead"))

  test("two dots are not the separator this endpoint accepts"):
    assertEquals(field(CompareRange.from("v1..v2")), Some("basehead"))

  test("a parsed range still rejects a traversal segment"):
    assertEquals(field(CompareRange.from("v1.../../admin")), Some("basehead"))

  private def range(base: String, head: String): CompareRange =
    CompareRange.between(orFail(RefName.from(base)), orFail(RefName.from(head)))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)

  private def orFail(result: Either[ValidationError, RefName]): RefName =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
