package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class BranchNameSuite extends FunSuite:

  test("accepts a plain branch name"):
    assertEquals(BranchName.from("forgejo").toOption.map(_.value), Some("forgejo"))

  test("accepts a slashed name, which golden/repository/branches-list.json proves is real"):
    val slashed = "renovate/forgejo-github.com-go-swagger-go-swagger-cmd-swagger-0.x"

    assertEquals(BranchName.from(slashed).toOption.map(_.value), Some(slashed))

  test("splits a slashed name into segments, because Forgejo routes it that way"):
    assertEquals(BranchName.from("v16.0/forgejo").toOption.map(_.segments), Some(List("v16.0", "forgejo")))

  test("a plain name is one segment"):
    assertEquals(BranchName.from("forgejo").toOption.map(_.segments), Some(List("forgejo")))

  test("trims surrounding whitespace"):
    assertEquals(BranchName.from("  forgejo  ").toOption.map(_.value), Some("forgejo"))

  test("rejects an empty value"):
    assertEquals(field(BranchName.from("")), Some("branch"))

  test("rejects a '..' segment, which survives encoding and would climb out of the route"):
    assertEquals(field(BranchName.from("../../admin/users")), Some("branch"))

  test("rejects a '.' segment"):
    assertEquals(field(BranchName.from("release/./v1")), Some("branch"))

  test("rejects a leading slash"):
    assertEquals(field(BranchName.from("/forgejo")), Some("branch"))

  test("rejects a trailing slash"):
    assertEquals(field(BranchName.from("forgejo/")), Some("branch"))

  test("rejects an empty middle segment"):
    assertEquals(field(BranchName.from("release//v1")), Some("branch"))

  test("rejects an embedded control character"):
    assertEquals(field(BranchName.from("forge\njo")), Some("branch"))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
