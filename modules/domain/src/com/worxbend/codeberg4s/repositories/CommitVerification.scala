package com.worxbend.codeberg4s.repositories

/** What the instance concluded about a commit's signature.
  *
  * The verdict is the instance's, computed against the keys it knows about, and it is not reproducible offline: the
  * same commit is unverified on an instance that has never seen the signing key. Treat [[isVerified]] as "this instance
  * says so", never as a cryptographic fact this library checked.
  *
  * @param isVerified
  *   whether the instance accepted the signature
  * @param reason
  *   the instance's explanation. On success it is the matched key, for example `viceice-bot / 8AC4BEEB5900F976`; on
  *   failure it is a Forgejo-internal token such as `gpg.error.no_gpg_keys_found`
  * @param signature
  *   the armoured signature, when the commit carries one
  * @param signer
  *   the identity the signature resolved to, when the instance resolved one
  * @param payload
  *   the exact bytes that were signed — the commit object as Git serialises it. Present so a caller can verify
  *   independently rather than take [[isVerified]] on trust
  */
final case class CommitVerification private[codeberg4s] (
    isVerified: Boolean,
    reason: Option[String],
    signature: Option[String],
    signer: Option[GitIdentity],
    payload: Option[String],
)
