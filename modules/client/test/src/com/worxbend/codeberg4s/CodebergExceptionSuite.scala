package com.worxbend.codeberg4s

import munit.FunSuite

/** The bridge between [[CodebergError]] and `Future`'s failure channel. */
final class CodebergExceptionSuite extends FunSuite:

  private val Context: CallContext =
    CallContext("repos.get", HttpMethod.Get, "https://codeberg.org/api/v1/repos/forgejo/forgejo", None, 12L)

  private val Failure: CodebergError =
    CodebergError.Api(Context, 404, ApiErrorBody(Some("The target couldn't be found."), None, Nil))

  test("the message is the error's redacted description"):
    assertEquals(CodebergException(Failure).getMessage, Failure.describe)

  test("pattern matching recovers the typed error, so the convenience rail loses nothing"):
    val recovered = CodebergException(Failure) match
      case CodebergException(error) => error

    assertEquals(recovered, Failure)

  test("it carries no stack trace, because it reports a remote answer and not a defect"):
    assertEquals(CodebergException(Failure).getStackTrace.length, 0)

  test("two exceptions carrying the same error are equal"):
    assertEquals(CodebergException(Failure), CodebergException(Failure))
