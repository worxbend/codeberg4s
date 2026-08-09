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
    assertEquals(config.maxResponseBodyBytes, CodebergConfig.DefaultMaxResponseBodyBytes)
    assertEquals(config.maxDownloadBodyBytes, CodebergConfig.DefaultMaxDownloadBodyBytes)

  test("the default response-body bound clears the largest JSON body Forgejo can produce"):
    // `default_max_blob_size` is 10 MiB (docs/HAZARDS.md §4), and a file-contents
    // response carries that blob base64-encoded, which costs four bytes per three.
    val largestBlobBase64 = 10L * 1024 * 1024 * 4 / 3

    assert(
      CodebergConfig.DefaultMaxResponseBodyBytes > largestBlobBase64,
      s"${CodebergConfig.DefaultMaxResponseBodyBytes} would reject a legitimate $largestBlobBase64-byte body",
    )

  test("the download bound is larger than the textual one, because an archive is not a JSON document"):
    assert(
      CodebergConfig.DefaultMaxDownloadBodyBytes > CodebergConfig.DefaultMaxResponseBodyBytes,
      "a download bound at or below the textual bound would make the two settings pointless",
    )

  test("a config carrying a token does not leak it through toString"):
    val rendered = CodebergConfig(Auth.Token(token(Secret))).toString

    assert(!rendered.contains(Secret), "the config leaked the token")
    assert(rendered.contains(ApiToken.Redacted), rendered)

  private def token(value: String): ApiToken =
    ApiToken.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid token in test setup: ${error.message}")
