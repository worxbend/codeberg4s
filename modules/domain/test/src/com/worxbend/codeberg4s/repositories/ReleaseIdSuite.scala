package com.worxbend.codeberg4s.repositories

import munit.FunSuite

final class ReleaseIdSuite extends FunSuite:

  test("accepts an identifier from the golden release capture"):
    assertEquals(ReleaseId.from(11189746L).toOption.map(_.value), Some(11189746L))

  test("accepts the smallest row id Forgejo issues"):
    assertEquals(ReleaseId.from(1L).toOption.map(_.value), Some(1L))

  test("rejects zero"):
    assertEquals(ReleaseId.from(0L).swap.toOption.map(_.field), Some("releaseId"))

  test("rejects a negative identifier"):
    assertEquals(ReleaseId.from(-1L).swap.toOption.map(_.field), Some("releaseId"))
