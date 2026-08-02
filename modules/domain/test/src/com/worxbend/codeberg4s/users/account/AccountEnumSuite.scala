package com.worxbend.codeberg4s.users.account

import munit.FunSuite

/** The three closed vocabularies this group introduces, and the one that is deliberately open at its edges.
  *
  * [[ObjectFormat]] and [[TrustModel]] are enumerated by `spec/swagger.v1.json`, so their `parse` answers `None`
  * outside the set rather than carrying an unknown value: they are only ever sent, never read back, so there is nothing
  * an `Other` case would preserve. [[RepositoryOrder]] is enumerated too, but adds one case the API does not declare —
  * [[RepositoryOrder.Default]], which is "send no `order_by`" and is the reason `wireValue` is an `Option`.
  */
final class AccountEnumSuite extends FunSuite:

  test("every object format round-trips through its wire spelling"):
    assertRoundTrips(ObjectFormat.values.toVector)(_.wireValue, ObjectFormat.parse)

  test("an object format outside the enumerated set is absence, not a failure"):
    assertEquals(ObjectFormat.parse("sha3"), None)

  test("object format parsing trims and ignores case"):
    assertEquals(ObjectFormat.parse("  SHA256 "), Some(ObjectFormat.Sha256))

  test("every trust model round-trips through its wire spelling"):
    assertRoundTrips(TrustModel.values.toVector)(_.wireValue, TrustModel.parse)

  test("the compound trust model is one lowercase word on the wire, as Forgejo spells it"):
    assertEquals(TrustModel.CollaboratorCommitter.wireValue, "collaboratorcommitter")

  test("a trust model outside the enumerated set is absence"):
    assertEquals(TrustModel.parse("anyone"), None)

  test("every repository ordering but the default has a wire spelling"):
    val spelled = RepositoryOrder.values.toVector.filterNot(isDefault).flatMap(_.wireValue)

    assertEquals(spelled.length, RepositoryOrder.values.length - 1)

  test("the default ordering sends nothing, which is how the instance's own ordering is asked for"):
    assertEquals(RepositoryOrder.Default.wireValue, None)

  test("no two orderings share a wire spelling"):
    val spelled = RepositoryOrder.values.toVector.flatMap(_.wireValue)

    assertEquals(spelled.distinct.length, spelled.length)

  test("the orderings this library spells are exactly the eighteen the spec enumerates"):
    assertEquals(RepositoryOrder.values.toVector.flatMap(_.wireValue).sorted, AccountEnumSuite.SpecOrderings)

  private def isDefault(order: RepositoryOrder): Boolean =
    order match
      case RepositoryOrder.Default => true
      case _                       => false

  private def assertRoundTrips[A](values: Vector[A])(render: A => String, parse: String => Option[A]): Unit =
    values.foreach(value => assertEquals(parse(render(value)), Some(value)))

object AccountEnumSuite:

  /** The `order_by` enum of `userCurrentListRepos`, copied verbatim from `spec/swagger.v1.json` and sorted. */
  private val SpecOrderings: Vector[String] =
    Vector(
      "name",
      "id",
      "newest",
      "oldest",
      "recentupdate",
      "leastupdate",
      "reversealphabetically",
      "alphabetically",
      "reversesize",
      "size",
      "reversegitsize",
      "gitsize",
      "reverselfssize",
      "lfssize",
      "moststars",
      "feweststars",
      "mostforks",
      "fewestforks",
    ).sorted
