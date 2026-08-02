package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.ValidationError

/** The instance-wide identifier of one OAuth2 application — the `{id}` of `/user/applications/oauth2/{id}`.
  *
  * `spec/swagger.v1.json` declares it `type: integer, format: int64` on the `GET`, `PATCH` and `DELETE` routes, and the
  * `OAuth2Application` model reports the same value as `id`. It is a database row id, so it is instance-wide and never
  * reused — which is exactly what lets [[com.worxbend.codeberg4s.users.account.UserApplicationApi.delete]] be retried;
  * see its Scaladoc for the argument.
  *
  * A number cannot forge a path, so this type is a confusion guard rather than an escaping one: an application id, a
  * hook id and a user id are all `Long` and all plausible values for one another, and passing one where another belongs
  * gets a `404` that reads like a missing resource rather than like a caller bug.
  */
opaque type OAuth2ApplicationId = Long

object OAuth2ApplicationId:

  private val MinValue: Long = 1L

  /** Parses an application identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"oauth2ApplicationId"` field
    */
  def from(value: Long): Either[ValidationError, OAuth2ApplicationId] =
    if value < MinValue then Left(ValidationError("oauth2ApplicationId", s"must be at least $MinValue"))
    else Right(value)

  extension (id: OAuth2ApplicationId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** One subject of a quota rule — the `subject` parameter of `GET /user/quota/check`.
  *
  * '''Not enumerated by the spec, and deliberately not enumerated here.''' `spec/swagger.v1.json` declares the
  * parameter as a bare required `type: string` and `QuotaRuleInfo.subjects` as an array of bare strings; it names no
  * values anywhere. Forgejo's own vocabulary is a dotted hierarchy — `size:all`, `size:repos:public`,
  * `size:assets:packages:all` and so on — but it is instance and release configuration, not part of the published
  * contract, so an `enum` here would be a list this library invented and a value a newer Forgejo added would be one
  * this library refused to ask about.
  *
  * What the type does do is stop a subject that cannot survive a query string: a blank value asks nothing, and a
  * control character corrupts the request. Both are rejected before a client is involved, which is why
  * [[com.worxbend.codeberg4s.users.account.UserQuotaApi.check]] cannot produce
  * [[com.worxbend.codeberg4s.CodebergError.Validation]]. A subject Forgejo does not recognise is its own judgement to
  * make and arrives as a `422`.
  */
opaque type QuotaSubject = String

object QuotaSubject:

  /** Parses a quota subject.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value and a value containing a control character. Nothing
    * else is checked; see the type note for why the vocabulary is not enumerated.
    *
    * @return
    *   the subject, or a [[ValidationError]] on the `"quotaSubject"` field
    */
  def from(value: String): Either[ValidationError, QuotaSubject] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError("quotaSubject", "must not be blank"))
    else if trimmed.exists(_.isControl) then
      Left(ValidationError("quotaSubject", "must not contain a control character"))
    else Right(trimmed)

  extension (subject: QuotaSubject)

    /** The subject as a string, ready to be sent as a query parameter. */
    def value: String = subject

/** One email address belonging to the authenticated account.
  *
  * An address is what both mutating email endpoints address: `POST /user/emails` adds the ones it names and
  * `DELETE /user/emails` removes the ones it names, neither of them by an id. That makes the address itself the
  * identifier of the thing, which is why it is a type and not a `String` — and why
  * [[com.worxbend.codeberg4s.users.account.UserAccountApi.deleteEmails]] can state an idempotency argument at all.
  *
  * '''Validated only as far as this library can honestly go.''' The address travels inside a JSON body, where it is
  * escaped, so it cannot forge a request; the checks below reject what is certainly not an address rather than
  * attempting RFC 5321. Deliverability, uniqueness and whether the domain is allowed are Forgejo's judgements and
  * arrive as a `422`.
  */
opaque type EmailAddress = String

object EmailAddress:

  /** The separator between the local part and the domain. */
  val At: Char = '@'

  /** Parses an email address.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing whitespace or a control
    * character, and a value that is not `local@domain` with both halves non-empty and exactly one `@`. Everything else
    * — length limits, quoting, internationalised domains, whether the address exists — is left to the instance.
    *
    * @return
    *   the address, or a [[ValidationError]] on the `"emailAddress"` field
    */
  def from(value: String): Either[ValidationError, EmailAddress] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError("emailAddress", "must not be blank"))
    else if trimmed.exists(character => character.isWhitespace || character.isControl) then
      Left(ValidationError("emailAddress", "must not contain whitespace or a control character"))
    else if !isAddressShaped(trimmed) then
      Left(ValidationError("emailAddress", s"must be of the form local${At}domain"))
    else Right(trimmed)

  extension (address: EmailAddress)

    /** The address as a string, ready to be rendered into a JSON body. */
    def value: String = address

  /** Whether `value` is `local@domain` with both halves non-empty and exactly one separator.
    *
    * Written with `indexOf` rather than `split` because universal equality is banned in this codebase, and comparing
    * the first and last separator positions is both cheaper and states the "exactly one" rule directly.
    */
  private def isAddressShaped(value: String): Boolean =
    val first = value.indexOf(At.toInt)
    val last  = value.lastIndexOf(At.toInt)

    first > 0 && last < value.length - 1 && last <= first
