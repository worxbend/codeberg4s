package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod

import munit.FunSuite

final class StatusMappingSuite extends FunSuite:

  private val context: CallContext =
    CallContext("issues.list", HttpMethod.Get, "https://codeberg.org/api/v1/repos/owner/name/issues", None, 7L)

  test("2xx is a success"):
    assert(StatusMapping.isSuccess(200))
    assert(StatusMapping.isSuccess(201))
    assert(StatusMapping.isSuccess(204))
    assert(StatusMapping.isSuccess(299))

  test("anything outside 2xx is not a success, redirects included"):
    assert(!StatusMapping.isSuccess(199))
    assert(!StatusMapping.isSuccess(301))
    assert(!StatusMapping.isSuccess(404))
    assert(!StatusMapping.isSuccess(500))

  test("429 is retryable"):
    assert(StatusMapping.isRetryable(429))

  test("the gateway 5xx family is retryable"):
    assert(StatusMapping.isRetryable(500))
    assert(StatusMapping.isRetryable(502))
    assert(StatusMapping.isRetryable(503))
    assert(StatusMapping.isRetryable(504))

  test("client errors are not retryable"):
    assert(!StatusMapping.isRetryable(400))
    assert(!StatusMapping.isRetryable(401))
    assert(!StatusMapping.isRetryable(403))
    assert(!StatusMapping.isRetryable(404))
    assert(!StatusMapping.isRetryable(409))
    assert(!StatusMapping.isRetryable(422))

  test("a 5xx describing what the instance cannot do is not retryable"):
    assert(!StatusMapping.isRetryable(501))
    assert(!StatusMapping.isRetryable(505))

  test("a success is never retryable"):
    assert(!StatusMapping.isRetryable(200))
    assert(!StatusMapping.isRetryable(204))

  test("toError keeps the status and the parsed body beside the call context"):
    val body = ApiErrorBody(Some("repository does not exist"), None, Nil)

    assertEquals(StatusMapping.toError(context, 404, body), CodebergError.Api(context, 404, body))
