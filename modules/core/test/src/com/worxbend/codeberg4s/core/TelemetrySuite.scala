package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class TelemetrySuite extends FunSuite:

  private val context: CallContext =
    CallContext("issues.list", HttpMethod.Get, "https://codeberg.org/api/v1/repos/owner/name/issues", None, 3L)

  private val telemetry: Telemetry[Exec.Result] = Telemetry.noOp[Exec.Result]

  test("the no-op sink succeeds on a request"):
    assertEquals(telemetry.onRequest(context), Right(()))

  test("the no-op sink succeeds on a response"):
    assertEquals(telemetry.onResponse(context, 200), Right(()))

  test("the no-op sink succeeds on an error"):
    val error = CodebergError.Validation(ValidationError("owner", "must not be blank"))

    assertEquals(telemetry.onError(context, error), Right(()))
