package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.auth.Auth

import munit.FunSuite

final class CodebergErrorSuite extends FunSuite:

  private val Context: CallContext = CallContext(
    operation  = "issues.list",
    method     = HttpMethod.Get,
    uri        = "https://codeberg.org/api/v1/repos/forgejo/forgejo",
    requestId  = Some("req-42"),
    durationMs = 12L,
  )

  test("describe names the operation, the method and the redacted uri"):
    val error: CodebergError = CodebergError.Transport(Context, TransportCause.Timeout("read timed out"))
    val described            = error.describe

    assert(described.contains("issues.list"), described)
    assert(described.contains("GET"), described)
    assert(described.contains(Context.uri), described)

  test("describe reports the status and the server message of an api failure"):
    val body                 = ApiErrorBody(Some("repository does not exist"), None, Nil)
    val error: CodebergError = CodebergError.Api(Context, 404, body)

    assert(error.describe.contains("404"), error.describe)
    assert(error.describe.contains("repository does not exist"), error.describe)

  test("describe copes with an api failure that carried no message"):
    val error: CodebergError = CodebergError.Api(Context, 500, ApiErrorBody.Empty)

    assert(error.describe.contains("500"), error.describe)

  test("describe lists the per-field errors of a validation response"):
    val body                 = ApiErrorBody(Some("invalid"), None, List("title is required", "body is too long"))
    val error: CodebergError = CodebergError.Api(Context, 422, body)

    assert(error.describe.contains("title is required"), error.describe)
    assert(error.describe.contains("body is too long"), error.describe)

  test("describe bounds an oversized body snippet at MaxSnippetLength"):
    val oversized            = "x".repeat(CodebergError.MaxSnippetLength * 3)
    val error: CodebergError =
      CodebergError.DecodingFailed(Context, oversized, JsonPath.of("items"), "expected an array")
    val described            = error.describe

    assert(described.contains("x".repeat(CodebergError.MaxSnippetLength)), "the snippet should survive up to the bound")
    assert(!described.contains("x".repeat(CodebergError.MaxSnippetLength + 1)), "the snippet should be truncated")
    assert(described.contains("..."), described)

  test("describe reports where decoding failed"):
    val error: CodebergError =
      CodebergError.DecodingFailed(Context, "{}", JsonPath.of("owner", "login"), "expected a string")

    assert(error.describe.contains("$.owner.login"), error.describe)

  test("describe of a validation failure names the field"):
    val error: CodebergError = CodebergError.Validation(ValidationError("pageSize", "must be between 1 and 50"))

    assert(error.describe.contains("pageSize"), error.describe)
    assert(error.describe.contains("must be between 1 and 50"), error.describe)

  test("describe of exhausted retries keeps the last failure"):
    val last: CodebergError  = CodebergError.Transport(Context, TransportCause.Dns("no such host"))
    val error: CodebergError = CodebergError.RetriesExhausted(Context, 3, last)
    val described            = error.describe

    assert(described.contains("3"), described)
    assert(described.contains("no such host"), described)

  test("describe never leaks a token that reached the error through the config"):
    val secret               = "cb-0123456789-super-secret"
    val config               = CodebergConfig(Auth.Token(token(secret)))
    val body                 = ApiErrorBody(Some(s"rejected credentials ${config.auth}"), None, Nil)
    val error: CodebergError =
      CodebergError.Api(Context.copy(uri = s"${config.baseUri.value}/repos/forgejo/forgejo"), 401, body)
    val described            = error.describe

    assert(!described.contains(secret), "describe leaked the token")
    assert(described.contains(ApiToken.Redacted), described)

  private def token(value: String): ApiToken =
    ApiToken.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid token in test setup: ${error.message}")
