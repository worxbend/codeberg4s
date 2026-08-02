package com.worxbend.codeberg4s.organizations

import munit.FunSuite

/** [[TeamPermission]] is the one closed set the pinned spec declares, so every declared spelling is asserted. */
final class TeamPermissionSuite extends FunSuite:

  test("parses every spelling the spec's enum declares"):
    val parsed = List("none", "read", "write", "admin", "owner").map(TeamPermission.parse)

    assertEquals(
      parsed,
      List(
        Some(TeamPermission.NoAccess),
        Some(TeamPermission.Read),
        Some(TeamPermission.Write),
        Some(TeamPermission.Admin),
        Some(TeamPermission.Owner),
      ),
    )

  test("parsing is case-insensitive and trims, because only the spec claims the casing"):
    assertEquals(TeamPermission.parse("  Write "), Some(TeamPermission.Write))

  test("an unrecognised level is absent rather than a failure, so a sixth Forgejo level costs nothing"):
    assertEquals(TeamPermission.parse("superuser"), None)

  test("a blank level is absent"):
    assertEquals(TeamPermission.parse("   "), None)

  test("every level round-trips through its wire spelling"):
    val roundTripped = TeamPermission.values.toList.map(level => TeamPermission.parse(level.wireName))

    assertEquals(roundTripped, TeamPermission.values.toList.map(Some.apply))

  test("no access is spelled none on the wire, even though the case is not called None"):
    assertEquals(TeamPermission.NoAccess.wireName, "none")

  test("the ranks run from no access to owner in Forgejo's order"):
    assertEquals(TeamPermission.values.toList.map(_.rank), List(0, 1, 2, 3, 4))

  test("a higher level allows everything a lower one allows"):
    assertEquals(TeamPermission.Admin.allows(TeamPermission.Write), true)
    assertEquals(TeamPermission.Admin.allows(TeamPermission.Admin), true)

  test("a lower level does not allow what a higher one allows"):
    assertEquals(TeamPermission.Read.allows(TeamPermission.Write), false)
    assertEquals(TeamPermission.NoAccess.allows(TeamPermission.Read), false)

  test("the levels are declared in Forgejo's own order, so a new one cannot be slipped into the middle unnoticed"):
    assertEquals(TeamPermission.values.toVector, Levels)

  test("allows is that ordering at all twenty-five pairs, not just at the two a sample would check"):
    val granted =
      for
        holder <- Levels.indices
        wanted <- Levels.indices
      yield describe(holder, wanted, Levels(holder).allows(Levels(wanted)))

    val expected =
      for
        holder <- Levels.indices
        wanted <- Levels.indices
      yield describe(holder, wanted, holder >= wanted)

    assertEquals(granted.toVector, expected.toVector)

  /** The five levels written out in the order this suite asserts, independently of [[TeamPermission.rank]] — comparing
    * `allows` against `rank` would only prove the implementation agrees with itself.
    */
  private val Levels: Vector[TeamPermission] =
    Vector(
      TeamPermission.NoAccess,
      TeamPermission.Read,
      TeamPermission.Write,
      TeamPermission.Admin,
      TeamPermission.Owner,
    )

  /** One cell of the `allows` table, spelled so that a failure names the pair rather than an index. */
  private def describe(holder: Int, wanted: Int, allowed: Boolean): String =
    s"${Levels(holder).wireName} allows ${Levels(wanted).wireName}: ${allowed.toString}"
