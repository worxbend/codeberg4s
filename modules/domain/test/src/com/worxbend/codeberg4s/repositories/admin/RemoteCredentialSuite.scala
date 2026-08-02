package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.RepoName

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
