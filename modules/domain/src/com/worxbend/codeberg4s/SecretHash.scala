package com.worxbend.codeberg4s

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The `hashCode` every credential type in this library shares.
  *
  * A credential type masks `toString`, but `hashCode` is another rendering path: a `HashMap` keyed by an
  * [[auth.ApiToken]], a `Set` of [[repositories.hooks.HookSecret]]s, or a `##` typed into a debugger all reach it, and
  * `String.hashCode` is not a one-way function of a short string. It is thirty-two bits of cheap arithmetic over the
  * characters, so a leaked hash of the material can be brute-forced back to a short secret offline, or simply compared
  * against the hash of a guess. None of that is worth handing out for the sake of a map key.
  *
  * Hashing a '''digest''' of the material instead keeps the property a map key needs — equal material hashes alike,
  * because SHA-256 is a function — while what escapes is thirty-two bits of a preimage-resistant digest, which says
  * nothing useful about the secret. Every credential type routes its `hashCode` here so the choice is made once; a
  * second copy of this rule would be a second place to forget it.
  *
  * '''What this is not.''' It is a redaction measure, not a cryptographic promise about equality: `equals` on each
  * credential type still compares the material with `String.equals`, which is not constant-time, and every such type
  * documents that. Nor does the digest hide the material from someone holding the value — [[auth.ApiToken.reveal]] and
  * its siblings exist for the transport, and this changes nothing about them.
  */
private[codeberg4s] object SecretHash:

  /** The name of the digest, as `java.security.MessageDigest` knows it. Every conforming JVM provides it. */
  private val Algorithm: String = "SHA-256"

  /** The first four bytes of the SHA-256 digest of `material`, read big-endian as an `Int`.
    *
    * A fresh `MessageDigest` per call: the class is not thread-safe, and a credential's `hashCode` may be asked for
    * from any thread.
    */
  def of(material: String): Int =
    val digest = MessageDigest.getInstance(Algorithm).digest(material.getBytes(StandardCharsets.UTF_8))
    ((digest(0) & 0xFF) << 24) | ((digest(1) & 0xFF) << 16) | ((digest(2) & 0xFF) << 8) | (digest(3) & 0xFF)
