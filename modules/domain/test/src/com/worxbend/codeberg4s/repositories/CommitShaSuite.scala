package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class CommitShaSuite extends FunSuite:

  private val Sha1: String = "647de8b3279b0ce6e9721cc651e431981725edf1"

  test("accepts a full SHA-1 object id"):
    assertEquals(CommitSha.from(Sha1).toOption.map(_.value), Some(Sha1))

  test("accepts a full SHA-256 object id, which Forgejo also supports"):
    val sha256 = "a" * 64

    assertEquals(CommitSha.from(sha256).toOption.map(_.value), Some(sha256))

  test("accepts an abbreviated id, as Git resolves one"):
    assertEquals(CommitSha.from("647de8b").toOption.map(_.value), Some("647de8b"))

  test("normalises to lowercase, so two spellings of one commit compare equal"):
    assertEquals(CommitSha.from(Sha1.toUpperCase).toOption, CommitSha.from(Sha1).toOption)

  test("trims surrounding whitespace"):
    assertEquals(CommitSha.from(s" $Sha1 ").toOption.map(_.value), Some(Sha1))

  test("short is the seven-character prefix Forgejo displays"):
    assertEquals(CommitSha.from(Sha1).toOption.map(_.short), Some("647de8b"))

  test("rejects an id shorter than Git will resolve"):
    assertEquals(field(CommitSha.from("64d")), Some("commitSha"))

  test("rejects an id longer than SHA-256"):
    assertEquals(field(CommitSha.from("a" * 65)), Some("commitSha"))

  test("rejects a non-hexadecimal id, which would otherwise reach a request path"):
    assertEquals(field(CommitSha.from("../../../etc/passwd")), Some("commitSha"))

  test("rejects an empty value"):
    assertEquals(field(CommitSha.from("")), Some("commitSha"))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
