package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.access.DeployKeyQuery

import munit.FunSuite

/** The query strings the two paged endpoints of this group send.
  *
  * Asserted as ordered lists of pairs rather than as maps, because
  * [[com.worxbend.codeberg4s.core.CodebergRequest.query]] is a list: order is part of what is sent, and a key may
  * legitimately repeat elsewhere in the library.
  */
final class AccessQueriesSuite extends FunSuite:

  test("paging always states both parameters, so a limit is never sent without a page"):
    assertEquals(AccessQueries.paging(window(2, 25)), List("page" -> "2", "limit" -> "25"))

  test("an empty deploy key query sends no filter at all"):
    assertEquals(AccessQueries.deployKeys(DeployKeyQuery.Empty), Nil)

  test("a deploy key query sends key_id under the spelling the spec declares"):
    assertEquals(AccessQueries.deployKeys(DeployKeyQuery.Empty.forKeyId(91L)), List("key_id" -> "91"))

  test("a deploy key query sends fingerprint under the spelling the spec declares"):
    assertEquals(
      AccessQueries.deployKeys(DeployKeyQuery.Empty.withFingerprint("SHA256:abc")),
      List("fingerprint" -> "SHA256:abc"),
    )

  test("both filters are emitted in the order the spec declares them"):
    assertEquals(
      AccessQueries.deployKeys(DeployKeyQuery.Empty.forKeyId(91L).withFingerprint("SHA256:abc")),
      List("key_id" -> "91", "fingerprint" -> "SHA256:abc"),
    )

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
