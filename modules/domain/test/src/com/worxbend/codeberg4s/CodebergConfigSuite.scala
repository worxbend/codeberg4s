package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.retry.RetryPolicy

import munit.FunSuite

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

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

  // --- invariants ---------------------------------------------------------------

  test("the one-argument constructor satisfies the invariants it documents"):
    val config = CodebergConfig(Auth.Anonymous)

    assertEquals(widened(config.validate), Right(config))

  test("the primary constructor does not check the invariants, so copy keeps compiling and constructing"):
    assertEquals(CodebergConfig(Auth.Anonymous).copy(readTimeout = -1.second).readTimeout, -1.second)

  test("validate rejects a zero connect timeout, on the connectTimeout field"):
    assertEquals(
      field(CodebergConfig(Auth.Anonymous).copy(connectTimeout = Duration.Zero).validate),
      Some("connectTimeout")
    )

  test("validate rejects a negative connect timeout"):
    assertEquals(field(CodebergConfig(Auth.Anonymous).copy(connectTimeout = -1.second).validate), Some("connectTimeout"))

  test("validate rejects a zero read timeout, on the readTimeout field"):
    assertEquals(field(CodebergConfig(Auth.Anonymous).copy(readTimeout = Duration.Zero).validate), Some("readTimeout"))

  test("validate rejects a negative read timeout"):
    assertEquals(field(CodebergConfig(Auth.Anonymous).copy(readTimeout = -1.second).validate), Some("readTimeout"))

  test("validate rejects a zero response-body bound, on the maxResponseBodyBytes field"):
    assertEquals(
      field(CodebergConfig(Auth.Anonymous).copy(maxResponseBodyBytes = 0L).validate),
      Some("maxResponseBodyBytes"),
    )

  test("validate rejects a negative response-body bound"):
    assertEquals(
      field(CodebergConfig(Auth.Anonymous).copy(maxResponseBodyBytes = -1L).validate),
      Some("maxResponseBodyBytes"),
    )

  test("validate rejects a zero download bound, on the maxDownloadBodyBytes field"):
    assertEquals(
      field(CodebergConfig(Auth.Anonymous).copy(maxDownloadBodyBytes = 0L).validate),
      Some("maxDownloadBodyBytes"),
    )

  test("validate accepts a bound of one byte, the smallest that lets anything through"):
    assertEquals(
      CodebergConfig(Auth.Anonymous).copy(maxResponseBodyBytes = 1L, maxDownloadBodyBytes = 1L).validate.isRight,
      true
    )

  test("validate reports a malformed retry policy on the policy's own field"):
    val config = CodebergConfig(Auth.Anonymous).copy(retry = RetryPolicy.Default.copy(maxAttempts = 0))

    assertEquals(field(config.validate), Some("maxAttempts"))

  test("of builds the same value the one-argument constructor would, when the invariants hold"):
    val built = CodebergConfig.of(
      baseUri              = BaseUri.Codeberg,
      auth                 = Auth.Anonymous,
      retry                = RetryPolicy.Default,
      userAgent            = UserAgent.Default,
      defaultPageSize      = PageSize.Default,
      connectTimeout       = CodebergConfig.DefaultConnectTimeout,
      readTimeout          = CodebergConfig.DefaultReadTimeout,
      maxResponseBodyBytes = CodebergConfig.DefaultMaxResponseBodyBytes,
      maxDownloadBodyBytes = CodebergConfig.DefaultMaxDownloadBodyBytes,
    )

    assertEquals(built, Right(CodebergConfig(Auth.Anonymous)))

  test("of refuses what validate refuses"):
    val built = CodebergConfig.of(
      baseUri              = BaseUri.Codeberg,
      auth                 = Auth.Anonymous,
      retry                = RetryPolicy.Default,
      userAgent            = UserAgent.Default,
      defaultPageSize      = PageSize.Default,
      connectTimeout       = CodebergConfig.DefaultConnectTimeout,
      readTimeout          = Duration.Zero,
      maxResponseBodyBytes = CodebergConfig.DefaultMaxResponseBodyBytes,
      maxDownloadBodyBytes = CodebergConfig.DefaultMaxDownloadBodyBytes,
    )

    assertEquals(field(built), Some("readTimeout"))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)

  /** `validate` answers `Either[ValidationError, config.type]`; munit's equality wants the plain type. */
  private def widened(result: Either[ValidationError, CodebergConfig]): Either[ValidationError, CodebergConfig] =
    result

  private def token(value: String): ApiToken =
    ApiToken.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid token in test setup: ${error.message}")
