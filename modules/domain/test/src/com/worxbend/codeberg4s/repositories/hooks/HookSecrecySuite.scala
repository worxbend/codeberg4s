package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** The redaction guarantee of the credential-bearing types in this group.
  *
  * This suite is the reason [[HookSecret]] is a final class rather than an opaque alias, and the reason [[HookConfig]]
  * has a private constructor. An `opaque type` over `String` has `Any` as its visible upper bound, so `toString` and
  * interpolation would dispatch to `String` outside the defining scope and print the material; and a `HookConfig` a
  * caller could build directly would be a map anyone could put a secret into. The assertions below are what make "a
  * webhook secret cannot reach a log" a property rather than a comment. It mirrors `ActionSecrecySuite`, deliberately.
  */
final class HookSecrecySuite extends FunSuite:

  private val Material: String = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg\n-----END PRIVATE KEY-----"

  test("a hook secret keeps its material verbatim, newlines included"):
    assertEquals(secret(Material).reveal, Material)

  test("a hook secret is not trimmed, because whitespace in a signing key can be significant"):
    assertEquals(secret("  padded  ").reveal, "  padded  ")

  test("a hook secret accepts a control character, because it travels escaped inside a JSON body"):
    assert(HookSecret.from("line\r\nline").isRight, "a multi-line secret must be accepted")

  test("a hook secret rejects an empty value, because Forgejo reads that as no signing at all"):
    assertEquals(HookSecret.from("").swap.toOption.map(_.field), Some("hookSecret"))

  test("the rejection message never echoes the rejected input"):
    val message = HookSecret.from("").swap.toOption.map(_.message)

    assert(!message.exists(_.contains("BEGIN")), message)

  test("toString renders the mask, never the material"):
    assertEquals(secret(Material).toString, HookSecret.Redacted)

  test("string interpolation renders the mask, never the material"):
    val interpolated = s"${secret(Material)}"

    assert(!interpolated.contains("PRIVATE"), "interpolation leaked the secret")
    assertEquals(interpolated, HookSecret.Redacted)

  test("redacted renders the mask"):
    assertEquals(secret(Material).redacted, HookSecret.Redacted)

  test("hook secrets with the same material are equal"):
    assertEquals(secret(Material), secret(Material))
    assertEquals(secret(Material).hashCode(), secret(Material).hashCode())

  test("hook secrets with different material are not equal"):
    assertNotEquals(secret(Material), secret("something else"))

  test("a hook secret is not equal to the bare string it wraps, so comparing one cannot unwrap it"):
    assert(!secret(Material).equals(Material), "a hook secret compared equal to its own material")
    assert(!secret(Material).equals(HookSecret.Redacted), "a hook secret compared equal to its mask")

  test("a create command holding a secret cannot print it through its generated toString"):
    val command = CreateHook.to(HookType.Forgejo, "https://ci.example/hook", HookContentType.Json).signedWith(
      secret(Material)
    )

    assert(!command.toString.contains("PRIVATE"), s"the generated toString leaked the secret: $command")

  test("an edit command holding an authorization header cannot print it either"):
    val command = EditHook.Empty.authorization(secret("token abc123"))

    assert(!command.toString.contains("abc123"), s"the generated toString leaked the header: $command")

  // --- the config is the other half of the guarantee -------------------------

  test("a config built from a payload drops a secret the instance sent back"):
    val config = HookConfig.from(Map("url" -> "https://ci.example", "secret" -> "hunter2"))

    assertEquals(config.valueOf(HookConfig.SecretKey), None)
    assert(!config.toString.contains("hunter2"), s"a secret survived into the config: $config")

  test("a config drops an authorization header the instance sent back"):
    val config = HookConfig.from(Map("authorization_header" -> "token abc123"))

    assertEquals(config.entries, Map.empty[String, String])

  test("the drop is case-insensitive, because a key is dropped on suspicion"):
    val config = HookConfig.from(Map("Secret" -> "hunter2", "AUTHORIZATION_HEADER" -> "token abc"))

    assert(!config.toString.contains("hunter2"), s"a differently-cased secret survived: $config")
    assertEquals(config.entries, Map.empty[String, String])

  test("a caller cannot put a secret into a config either"):
    val config = HookConfig.of("https://ci.example", HookContentType.Json).withEntry("secret", "hunter2")

    assertEquals(config.valueOf(HookConfig.SecretKey), None)
    assertEquals(config.url, Some("https://ci.example"))

  test("an ordinary entry is kept, so the drop is targeted and not a blanket refusal"):
    val config = HookConfig.of("https://ci.example", HookContentType.Json).withEntry("channel", "#builds")

    assertEquals(config.valueOf("channel"), Some("#builds"))
    assertEquals(config.contentType, Some(HookContentType.Json))

  test("config entries render in key order, so a request body is reproducible"):
    val config = HookConfig.from(Map("url" -> "u", "content_type" -> "json", "channel" -> "c"))

    assertEquals(config.sortedEntries.map((key, _) => key), Vector("channel", "content_type", "url"))

  private def secret(value: String): HookSecret =
    orFail(HookSecret.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
