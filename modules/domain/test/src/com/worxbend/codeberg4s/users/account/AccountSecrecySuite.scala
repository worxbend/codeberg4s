package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

import java.time.Instant

/** The redaction guarantee of [[ClientSecret]], the one credential in this group that travels out of the instance.
  *
  * This suite is the reason it is a final class rather than an opaque alias. An `opaque type` over `String` has `Any`
  * as its visible upper bound, so `toString` and interpolation would dispatch to `String` outside the defining scope
  * and print the material — the assertions below are what makes "a client secret cannot reach a log" a property rather
  * than a comment. It mirrors `ActionSecrecySuite` and `HookSecrecySuite`, deliberately.
  *
  * The last three tests are the ones the group's brief singles out: a client secret must not be able to reach a
  * [[com.worxbend.codeberg4s.CodebergError]] or any `toString`, and it is checked here rather than argued about.
  */
final class AccountSecrecySuite extends FunSuite:

  private val Material: String = "gto_51f0c9a2e7b34d6f8a1c2e3b4d5f6a7b8c9d0e1f"

  test("a client secret keeps its material verbatim"):
    assertEquals(secret(Material).reveal, Material)

  test("a client secret is trimmed, because it is copied into a configuration file"):
    assertEquals(secret(s"  $Material  ").reveal, Material)

  test("a client secret rejects a blank value"):
    assertEquals(ClientSecret.from("   ").swap.toOption.map(_.field), Some("clientSecret"))

  test("the rejection message never echoes the rejected input"):
    val message = ClientSecret.from("   ").swap.toOption.map(_.message)

    assert(!message.exists(_.contains("gto_")), message)

  test("toString renders the mask, never the material"):
    assertEquals(secret(Material).toString, ClientSecret.Redacted)

  test("string interpolation renders the mask, never the material"):
    val interpolated = s"${secret(Material)}"

    assert(!interpolated.contains("gto_"), "interpolation leaked the client secret")
    assertEquals(interpolated, ClientSecret.Redacted)

  test("redacted renders the mask"):
    assertEquals(secret(Material).redacted, ClientSecret.Redacted)

  test("client secrets with the same material are equal"):
    assertEquals(secret(Material), secret(Material))
    assertEquals(secret(Material).hashCode(), secret(Material).hashCode())

  test("client secrets with different material are not equal"):
    assertNotEquals(secret(Material), secret("something else"))

  test("the generated toString of the application holding a client secret cannot print it"):
    val rendered = application(Some(secret(Material))).toString

    assert(!rendered.contains("gto_"), s"the generated toString leaked the client secret: $rendered")
    assert(rendered.contains(ClientSecret.Redacted), s"the mask is missing from $rendered")

  test("an application carries its secret only when one was decoded"):
    assertEquals(application(Some(secret(Material))).carriesSecret, true)
    assertEquals(application(None).carriesSecret, false)

  test("a client secret cannot reach a CodebergError, because no error is built from a value that carries one"):
    val failure = decodingFailure(application(Some(secret(Material))))

    assert(!failure.describe.contains("gto_"), s"the failure leaked the client secret: ${failure.describe}")

  test("a client secret cannot reach a CodebergError through an error body either"):
    val failure = apiFailure(application(Some(secret(Material))))

    assert(!failure.describe.contains("gto_"), s"the failure leaked the client secret: ${failure.describe}")

  private def secret(value: String): ClientSecret =
    orFail(ClientSecret.from(value))

  private def application(material: Option[ClientSecret]): OAuth2Application =
    OAuth2Application(
      id                   = orFail(OAuth2ApplicationId.from(7L)),
      name                 = Some("deploy-bot"),
      clientId             = Some("2fd3a1c0-1111-2222-3333-444455556666"),
      clientSecret         = material,
      redirectUris         = Vector("https://ci.example/oauth/callback"),
      isConfidentialClient = true,
      createdAt            = Some(Instant.parse("2026-07-30T19:14:15Z")),
    )

  /** The worst case a decoding failure can carry: the whole rendered application as a body snippet. */
  private def decodingFailure(value: OAuth2Application): CodebergError =
    CodebergError.DecodingFailed(context, value.toString, JsonPath.Root.field("client_secret"), "unreadable")

  /** The worst case an API failure can carry: the rendered application as the message of an error body. */
  private def apiFailure(value: OAuth2Application): CodebergError =
    CodebergError.Api(context, 422, ApiErrorBody(Some(value.toString), None, Nil))

  private def context: CallContext =
    CallContext("users.account.applications.create", HttpMethod.Post, "https://forge.example/api/v1/user", None, 3L)

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
