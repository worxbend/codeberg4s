package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** The redaction guarantee of [[RemoteCredential]], and of the two commands that hold one.
  *
  * This suite is the reason `RemoteCredential` is a final class rather than an opaque alias over `String`. An
  * `opaque type` has `Any` as its visible upper bound, so `toString` and interpolation would dispatch to `String`
  * outside the defining scope and print the credential — the assertions below are what makes "a remote credential
  * cannot reach a log" a property rather than a comment.
  *
  * The last two tests are the ones that matter operationally: a caller who logs a whole [[MigrateRepository]] or
  * [[CreatePushMirror]] — which is exactly what a debug line looks like — must not thereby log somebody else's GitHub
  * token. It mirrors `ActionSecrecySuite`, deliberately.
  */
final class RemoteCredentialSuite extends FunSuite:

  private val Material: String = "ghp_0123456789abcdefSECRET"

  test("a remote credential keeps its material verbatim"):
    assertEquals(credential(Material).reveal, Material)

  test("a remote credential is not trimmed, because a password may legitimately start with a space"):
    assertEquals(credential("  padded  ").reveal, "  padded  ")

  test("a remote credential accepts a newline, because a token pasted from a file may carry one"):
    assert(RemoteCredential.from("line\nline").isRight, "a multi-line credential must be accepted")

  test("a remote credential rejects an empty value, which is almost always an unset variable"):
    assertEquals(RemoteCredential.from("").swap.toOption.map(_.field), Some("remoteCredential"))

  test("the rejection message never echoes the rejected input"):
    val message = RemoteCredential.from("").swap.toOption.map(_.message)

    assert(!message.exists(_.contains("ghp_")), message)

  test("toString renders the mask, never the material"):
    assertEquals(credential(Material).toString, RemoteCredential.Redacted)

  test("string interpolation renders the mask, never the material"):
    val interpolated = s"${credential(Material)}"

    assert(!interpolated.contains("SECRET"), "interpolation leaked the credential")
    assertEquals(interpolated, RemoteCredential.Redacted)

  test("redacted renders the mask"):
    assertEquals(credential(Material).redacted, RemoteCredential.Redacted)

  test("credentials with the same material are equal, so a configuration value stays comparable"):
    assertEquals(credential(Material), credential(Material))
    assertEquals(credential(Material).hashCode(), credential(Material).hashCode())

  test("credentials with different material are not equal"):
    assertNotEquals(credential(Material), credential("something else"))

  test("a credential is not equal to the bare string it wraps, so comparing one cannot unwrap it"):
    assert(!credential(Material).equals(Material), "a credential compared equal to its own material")
    assert(!credential(Material).equals(RemoteCredential.Redacted), "a credential compared equal to its mask")
    assert(!credential(Material).equals(Option(Material)), "a credential compared equal to a foreign value")

  test("a failure describing a call that carried a credential cannot print it, whatever built the failure"):
    val command = MigrateRepository
      .from("https://github.com/a/b.git", repo("b"))
      .authenticatedAs("octocat", credential(Material))
    val context = CallContext(
      operation  = "repositories.migrate",
      method     = HttpMethod.Post,
      uri        = "https://codeberg.org/api/v1/repos/migrate",
      requestId  = None,
      durationMs = 12L,
    )
    val failure = CodebergError.DecodingFailed(
      ctx     = context,
      snippet = s"$command",
      path    = JsonPath.of("clone_addr"),
      cause   = s"expected an object, and the request was $command",
    )

    assert(!failure.describe.contains("SECRET"), s"describe leaked the credential: ${failure.describe}")
    assert(failure.describe.contains(RemoteCredential.Redacted), "the mask should appear where the credential was")

  test("a rejected credential is described by its field alone, and the failure never carries material"):
    val failure = RemoteCredential.from("").swap.toOption.map(error => CodebergError.Validation(error).describe)

    assertEquals(failure, Some("invalid remoteCredential: must not be empty"))

  test("a migrate command holding a credential cannot print it"):
    val command = MigrateRepository
      .from("https://github.com/a/b.git", repo("b"))
      .authenticatedAs("octocat", credential(Material))
      .authenticatedWith(credential(Material))

    assert(!command.toString.contains("SECRET"), s"the generated toString leaked the credential: $command")
    assert(command.toString.contains(RemoteCredential.Redacted), "the mask should appear where the credential was")

  test("a push-mirror command holding a credential cannot print it either"):
    val command = CreatePushMirror.to("https://example.test/a/b.git").authenticatedAs("bot", credential(Material))

    assert(!command.toString.contains("SECRET"), s"the generated toString leaked the credential: $command")

  private def credential(value: String): RemoteCredential =
    RemoteCredential.from(value) match
      case Right(secret) => secret
      case Left(error)   => fail(s"invalid fixture: ${error.field} ${error.message}")

  private def repo(value: String): RepoName =
    RepoName.from(value) match
      case Right(name) => name
      case Left(error) => fail(s"invalid fixture: ${error.field} ${error.message}")

  test("a validation error from this type names only its own field"):
    val failure: Option[ValidationError] = RemoteCredential.from("").swap.toOption

    assertEquals(failure.map(_.field), Some("remoteCredential"))
