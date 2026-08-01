package com.worxbend.codeberg4s.miscellaneous

/** The instance's default OpenPGP public key, as `GET /signing-key.gpg` serves it.
  *
  * This is the key Forgejo signs commits and tags with when a repository asks it to. A caller verifies a signature
  * against it; this library does not parse it, because parsing an armored key is a job for a cryptography library and a
  * half-parsed key is worse than an opaque one. The value is the response body verbatim, armor headers, checksum line
  * and trailing newline included, so it can be handed to `gpg --import` or to Bouncy Castle unchanged.
  *
  * @param armored
  *   the ASCII-armored key block exactly as the instance sent it, never blank
  */
final case class SigningKey(armored: String)

object SigningKey:

  /** Reads a `signing-key.gpg` body.
    *
    * An instance with no signing key configured answers `200` with an '''empty body''' rather than `404` — Forgejo
    * writes out whatever `PublicSigningKey` returned, and that is the empty string when signing is off. Absence is
    * therefore a normal outcome of a successful call, and it is reported as `None` rather than as a decoding failure:
    * nothing about the response is malformed.
    *
    * The body is kept verbatim; only the emptiness test ignores surrounding whitespace.
    */
  def from(body: String): Option[SigningKey] =
    Option.when(body.trim.nonEmpty)(SigningKey(body))
