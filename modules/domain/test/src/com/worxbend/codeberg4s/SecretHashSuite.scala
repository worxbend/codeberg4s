package com.worxbend.codeberg4s

import munit.FunSuite

/** [[SecretHash]]: the one `hashCode` every credential type routes through.
  *
  * Two properties matter and they pull in opposite directions. Equal material must hash alike, or a credential cannot
  * be a map key; and the hash must not be `String.hashCode` of the material, or a hash-keyed collection would hand out
  * a cheaply invertible function of the secret.
  */
final class SecretHashSuite extends FunSuite:

  private val Material: String = "cb-0123456789abcdef-secret"

  test("equal material hashes alike, so a credential can key a map"):
    assertEquals(SecretHash.of(Material), SecretHash.of(Material))

  test("the hash is not String.hashCode of the material"):
    assertNotEquals(SecretHash.of(Material), Material.hashCode)

  test("the hash is not String.hashCode of the material for the empty string either"):
    assertNotEquals(SecretHash.of(""), "".hashCode)

  test("different material hashes differently, so two secrets do not collide by construction"):
    assertNotEquals(SecretHash.of(Material), SecretHash.of(s"$Material!"))

  test("the hash is stable across calls, because the digest is a function and not a salt"):
    assertEquals(Vector.fill(3)(SecretHash.of(Material)).distinct.length, 1)
