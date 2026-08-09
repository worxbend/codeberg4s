package com.worxbend.codeberg4s.miscellaneous

/** The instance's default SSH signing key, as `GET /signing-key.ssh` serves it.
  *
  * The SSH counterpart of [[SigningKey]]: this is the key Forgejo signs commits and tags with on a deployment
  * configured for SSH signing rather than OpenPGP. As there, the value is the response body verbatim so it can be
  * appended to an `allowed_signers` file or handed to `ssh-keygen -Y verify` unchanged, and as there this library
  * parses nothing — a half-parsed public key is worse than an opaque one.
  *
  * '''A separate type from [[SigningKey]], not a second constructor on it.''' The two carry different formats: an
  * OpenPGP block begins `-----BEGIN PGP PUBLIC KEY BLOCK-----` and spans many lines, an OpenSSH authorized-key line is
  * one line beginning `ssh-ed25519` or `ssh-rsa`. Sharing a type would mean a field called `armored` holding something
  * that is not armored, and would let a value fetched from one endpoint be handed to a verifier that only understands
  * the other. The endpoints are separate; the types are separate.
  *
  * @param openSsh
  *   the public key in OpenSSH authorized-key format, exactly as the instance sent it, never blank
  */
final case class SshSigningKey private[codeberg4s] (openSsh: String)

object SshSigningKey:

  /** Reads a `signing-key.ssh` body.
    *
    * '''`None` is a success.''' An instance with no SSH signing key configured answers `200` with an '''empty body''',
    * exactly as `/signing-key.gpg` does — see [[SigningKey.from]]. Nothing about that response is malformed, so it is
    * reported as an absent key and not as a decoding failure.
    *
    * '''This endpoint can also answer `404`''', which `/signing-key.gpg` cannot: the pinned spec declares one for
    * `getSSHSigningKey` and none for `getSigningKey`. A `404` is a remote failure like any other and never reaches this
    * method — it becomes [[com.worxbend.codeberg4s.CodebergError.Api]]. So a caller sees two different shapes of "there
    * is no SSH signing key", one on each rail, and only the `200` one is modelled here.
    *
    * The body is kept verbatim; only the emptiness test ignores surrounding whitespace.
    */
  def from(body: String): Option[SshSigningKey] =
    Option.when(body.trim.nonEmpty)(SshSigningKey(body))
