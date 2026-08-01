package com.worxbend.codeberg4s.miscellaneous

import munit.FunSuite

final class SigningKeySuite extends FunSuite:

  test("an armored block is kept exactly as the instance sent it"):
    assertEquals(SigningKey.from(SigningKeySuite.Armored), Some(SigningKey(SigningKeySuite.Armored)))

  test("an instance that signs nothing answers with an empty body, which is absence and not a failure"):
    assertEquals(SigningKey.from(""), None)

  test("a body of nothing but whitespace is absence too"):
    assertEquals(SigningKey.from("\n  \n"), None)

object SigningKeySuite:

  /** The shape of a `signing-key.gpg` body: armor header, body, checksum, armor tail, trailing newline. Not a real key
    * — this is a client library, and no test here needs one.
    */
  private val Armored: String =
    """-----BEGIN PGP PUBLIC KEY BLOCK-----
      |
      |mDMEZQEAAAAAAA
      |=abcd
      |-----END PGP PUBLIC KEY BLOCK-----
      |""".stripMargin
