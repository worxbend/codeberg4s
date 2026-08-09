package com.worxbend.codeberg4s.users.account

/** One email address on the authenticated account, as `GET /user/emails` reports it.
  *
  * '''Derived from `spec/swagger.v1.json`'s `Email` definition, not from a captured response.''' The golden harvest
  * behind `modules/codec/test/resources/golden` was anonymous and every `/user/emails` route requires a token, so no
  * fixture exists for this model; see [[OAuth2Application]] for the full statement of what that means.
  *
  * ==Why the two flags are not `Option[Boolean]`==
  *
  * `primary` and `verified` are the two things a caller actually decides on — which address receives notifications, and
  * whether the address may be used at all. Forgejo sends both as bare booleans and the spec declares neither required,
  * so an absent key could be modelled as unknown. It is not, because the useful reading of "the instance did not say
  * this address is primary" is "it is not primary": a `false` a caller can act on beats an `Option` every one of them
  * would have to `getOrElse(false)` anyway. The identifier fields keep their `Option`, because there the distinction is
  * real.
  *
  * @param address
  *   the address itself, which is also what the add and remove endpoints name; see [[EmailAddress]]
  * @param isPrimary
  *   whether this is the account's primary address. `false` when the instance did not say; see the class note
  * @param isVerified
  *   whether the address has been confirmed. `false` when the instance did not say
  * @param userId
  *   the account the address belongs to. Absent when the payload carried none, which is the ordinary case on
  *   `/user/emails` — the account is the one the credentials belong to and does not need restating
  * @param username
  *   the account's login, on the same terms as [[userId]]
  */
final case class Email private[codeberg4s] (
    address: EmailAddress,
    isPrimary: Boolean,
    isVerified: Boolean,
    userId: Option[Long],
    username: Option[String],
)
