package com.worxbend.codeberg4s.miscellaneous

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.RepoSlug

import munit.FunSuite

final class MarkdownContextSuite extends FunSuite:

  test("accepts a repository path"):
    assertEquals(MarkdownContext.from("forgejo/forgejo").toOption.map(_.value), Some("forgejo/forgejo"))

  test("accepts an absolute URL, which Forgejo also takes"):
    assertEquals(
      MarkdownContext.from("https://codeberg.org/forgejo/forgejo").toOption.map(_.value),
      Some("https://codeberg.org/forgejo/forgejo"),
    )

  test("trims surrounding whitespace"):
    assertEquals(MarkdownContext.from("  forgejo/forgejo  ").toOption.map(_.value), Some("forgejo/forgejo"))

  test("rejects an empty value"):
    assertEquals(field(MarkdownContext.from("")), Some("markdownContext"))

  test("rejects a blank value"):
    assertEquals(field(MarkdownContext.from("   ")), Some("markdownContext"))

  test("rejects an embedded newline, which would corrupt the request body"):
    assertEquals(field(MarkdownContext.from("forgejo/for\ngejo")), Some("markdownContext"))

  test("a validated slug becomes a context without a second validation"):
    val slug = RepoSlug(orFail(Owner.from("codeberg")), orFail(RepoName.from("Community")))

    assertEquals(MarkdownContext.of(slug).value, "codeberg/Community")

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
