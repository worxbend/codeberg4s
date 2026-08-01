package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.ApiErrorBody

import munit.FunSuite

/** Every captured error body, plus the shapes the capture session could not produce.
  *
  * The contract under test is narrow and absolute: this parser has no failure mode. An error body is already the
  * explanation of a failure, so a second failure while reading it would hide the status code that carried it.
  */
final class ApiErrorBodyCodecSuite extends FunSuite with GoldenFixtures:

  /** The `404` captured verbatim in `docs/HAZARDS.md` §4, from
    * `GET https://codeberg.org/api/v1/repos/definitely/nonexistent-xyz`.
    *
    * It is the only observed body with a populated `errors` array, and it is the reason `errors[0]` matters: `message`
    * is the Go function name that failed, and the sentence a human can read is in `errors`. The capture session that
    * produced the golden fixtures happened to hit paths that answer with an empty `errors`, so this body is kept inline
    * rather than lost.
    */
  private val PopulatedErrors: String =
    """{"message":"GetUserByName","url":"https://codeberg.org/api/swagger",""" +
      """"errors":["user redirect does not exist [name: definitely]"]}"""

  test("errors[0] survives parsing"):
    val parsed = ApiErrorBodyCodec.parse(PopulatedErrors)

    assertEquals(parsed.errors, List("user redirect does not exist [name: definitely]"))
    assertEquals(parsed.message, Some("GetUserByName"))
    assertEquals(parsed.url, Some("https://codeberg.org/api/swagger"))

  test("golden 404 for a missing repository"):
    val parsed = ApiErrorBodyCodec.parse(golden("error/404-repo-not-found.json"))

    assertEquals(parsed.message, Some("The target couldn't be found."))
    assertEquals(parsed.url, Some("https://codeberg.org/api/swagger"))
    assertEquals(parsed.errors, Nil)

  test("golden 404 for a missing user"):
    val parsed = ApiErrorBodyCodec.parse(golden("error/404-user-not-found.json"))

    assertEquals(parsed.message, Some("user redirect does not exist [name: codeberg4s-no-such-user-xyz]"))
    assertEquals(parsed.errors, Nil)

  test("golden 401 from an unauthenticated /user"):
    val parsed = ApiErrorBodyCodec.parse(golden("error/401-token-required.json"))

    assertEquals(parsed.message, Some("token is required"))
    assertEquals(parsed.errors, Nil)

  test("golden 401 from org teams"):
    assertEquals(ApiErrorBodyCodec.parse(golden("error/401-org-teams.json")).message, Some("token is required"))

  test("golden 401 from user orgs"):
    assertEquals(ApiErrorBodyCodec.parse(golden("error/401-user-orgs.json")).message, Some("token is required"))

  test("golden 422 carries a raw Go error string as its message"):
    val parsed = ApiErrorBodyCodec.parse(golden("error/422-invalid-sort.json"))

    assertEquals(parsed.message, Some("""Invalid sort mode: "bogus""""))
    assertEquals(parsed.errors, Nil)

  test("a router-level plain-text body degrades to Empty rather than failing"):
    assertEquals(ApiErrorBodyCodec.parse("404 page not found"), ApiErrorBody.Empty)

  test("an HTML proxy page degrades to Empty"):
    assertEquals(ApiErrorBodyCodec.parse("<html><body>502 Bad Gateway</body></html>"), ApiErrorBody.Empty)

  test("an empty body degrades to Empty"):
    assertEquals(ApiErrorBodyCodec.parse(""), ApiErrorBody.Empty)

  test("a truncated JSON body degrades to Empty"):
    assertEquals(ApiErrorBodyCodec.parse("""{"message":"token is req"""), ApiErrorBody.Empty)

  test("a JSON body that is not an object degrades to Empty"):
    assertEquals(ApiErrorBodyCodec.parse("""["token is required"]"""), ApiErrorBody.Empty)
    assertEquals(ApiErrorBodyCodec.parse("""null"""), ApiErrorBody.Empty)

  test("an absent errors key and a null errors key are the same empty list"):
    val withoutKey = ApiErrorBodyCodec.parse("""{"message":"boom"}""")
    val withNull   = ApiErrorBodyCodec.parse("""{"message":"boom","errors":null}""")

    assertEquals(withoutKey, withNull)
    assertEquals(withoutKey.errors, Nil)

  test("a blank message is absence, not an empty explanation"):
    assertEquals(ApiErrorBodyCodec.parse("""{"message":"","url":""}"""), ApiErrorBody.Empty)

  test("errors entries that are not strings are dropped rather than failing"):
    val parsed = ApiErrorBodyCodec.parse("""{"errors":["real", 7, null, {"nested":true}, "also real"]}""")

    assertEquals(parsed.errors, List("real", "also real"))

  test("a message of the wrong JSON kind does not cost the errors array"):
    val parsed = ApiErrorBodyCodec.parse("""{"message":{"go":"symbol"},"errors":["the useful part"]}""")

    assertEquals(parsed.message, None)
    assertEquals(parsed.errors, List("the useful part"))

  test("unknown keys a future Forgejo adds are ignored"):
    val parsed = ApiErrorBodyCodec.parse("""{"message":"boom","trace_id":"abc","retry_after":30}""")

    assertEquals(parsed.message, Some("boom"))
