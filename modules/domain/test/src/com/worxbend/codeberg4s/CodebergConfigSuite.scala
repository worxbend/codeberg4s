package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.retry.RetryPolicy

import munit.FunSuite

final class CodebergConfigSuite extends FunSuite:

  private val Secret: String = "cb-0123456789abcdef-secret"

  test("the one-argument constructor targets Codeberg"):
    val config = CodebergConfig(Auth.Anonymous)

    assertEquals(config.baseUri.value, BaseUri.Codeberg.value)

  test("the one-argument constructor keeps the supplied auth"):
    val auth = Auth.Token(token(Secret))

    assertEquals(CodebergConfig(auth).auth, auth)

  test("the one-argument constructor uses the library defaults"):
    val config = CodebergConfig(Auth.Anonymous)

    assertEquals(config.retry, RetryPolicy.Default)
    assertEquals(config.userAgent.value, UserAgent.Default.value)
    assertEquals(config.defaultPageSize.value, PageSize.Default.value)
    assertEquals(config.connectTimeout, CodebergConfig.DefaultConnectTimeout)
    assertEquals(config.readTimeout, CodebergConfig.DefaultReadTimeout)

  test("a config carrying a token does not leak it through toString"):
    val rendered = CodebergConfig(Auth.Token(token(Secret))).toString

    assert(!rendered.contains(Secret), "the config leaked the token")
    assert(rendered.contains(ApiToken.Redacted), rendered)

  private def token(value: String): ApiToken =
    ApiToken.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid token in test setup: ${error.message}")
