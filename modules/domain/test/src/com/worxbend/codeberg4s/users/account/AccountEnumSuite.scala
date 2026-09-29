package com.worxbend.codeberg4s.users.account

import munit.FunSuite

/** The one closed vocabulary this group introduces.
  *
  * [[RepositoryOrder]] is enumerated by `spec/swagger.v1.json`, but adds one case the API does not declare —
  * [[RepositoryOrder.Default]], which is "send no `order_by`" and is the reason `wireValue` is an `Option`. The object
  * format and trust model a `CreateRepository` carries are `repositories.admin.ObjectFormat` and
  * `repositories.admin.TrustModel`, tested in `AdminVocabularySuite`.
  */
final class AccountEnumSuite extends FunSuite:

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
