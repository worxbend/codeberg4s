package com.worxbend.codeberg4s.miscellaneous

import munit.FunSuite

/** [[SshSigningKey]] reads a `text/plain` body, and the interesting case is the one that looks like a failure and is
  * not.
  */
final class SshSigningKeySuite extends FunSuite:

  private val Key: String = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyMaterialForATest forgejo\n"

  test("an authorized-key line is kept verbatim, trailing newline included"):
    assertEquals(SshSigningKey.from(Key), Some(SshSigningKey(Key)))

  test("an instance that signs nothing answers an empty body, which is absence and not a failure"):
    assertEquals(SshSigningKey.from(""), None)

  test("a body of nothing but whitespace is absence too"):
    assertEquals(SshSigningKey.from("\n  \n"), None)

  test("nothing is trimmed off a key that is present, because the body is handed on for a parser to read"):
    assertEquals(SshSigningKey.from(s"  $Key").map(_.openSsh), Some(s"  $Key"))

  test("it reads a body exactly as SigningKey does, which is why only the type and the field name differ"):
    assertEquals(SshSigningKey.from(Key).map(_.openSsh), SigningKey.from(Key).map(_.armored))
    assertEquals(SshSigningKey.from("").map(_.openSsh), SigningKey.from("").map(_.armored))
