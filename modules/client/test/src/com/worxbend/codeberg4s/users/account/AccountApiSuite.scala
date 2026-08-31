package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.CodebergError

import munit.FunSuite

/** What the five account API suites share on top of the module-wide [[ClientSuiteHarness]].
  *
  * The stub backend, the pipeline and the request assertions live in [[ClientSuiteHarness]]. What is left here is the
  * `/user` prefix every path in this group is built on, the decoding-failure accessor only these suites need, and the
  * error bodies they stub.
  *
  * The subject of every suite is the wiring: which URI is dialled, which query parameters and which body are sent,
  * which calls may be repeated, and what each rail does with a failure. Decoding itself is asserted in `modules/codec`,
  * so the payloads are small hand-written bodies chosen to exercise a seam.
  *
  * '''No golden fixture backs this group.''' Every payload in these suites was written from `spec/swagger.v1.json`;
  * every endpoint under `/user` requires a token and the golden harvest was anonymous.
  */
abstract class AccountApiSuite extends FunSuite with ClientSuiteHarness:

  /** The `/user` root every path in this group is built on. */
  protected val Endpoint: String = s"$Root/user"

  /** The JSON path a decoding failure blames. */
  protected def decodingPathOf[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.DecodingFailed(_, _, path, _)) => path.render
      case other                                             => fail(s"expected a decoding failure, got $other")

object AccountApiSuite:

  /** The `401` body an endpoint under `/user` answers to an anonymous caller, as `golden/error/401-token-required.json`
    * captured it.
    */
  val UnauthorizedBody: String =
    """{"message":"token is required","url":"https://codeberg.org/api/swagger"}"""

  /** A `403` body, as Forgejo words a missing token scope. */
  val ForbiddenBody: String =
    """{"message":"token does not have at least one of required scope(s): [write:user]"}"""

  /** A `404` body for an object of this group the account does not own. */
  val NotFoundBody: String =
    """{"message":"not found","url":"https://codeberg.org/api/swagger","errors":["does not exist"]}"""
