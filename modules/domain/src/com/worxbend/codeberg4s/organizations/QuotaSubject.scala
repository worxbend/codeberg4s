package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.ValidationError

/** What a quota question is about — the required `subject` parameter of `GET /orgs/{org}/quota/check`.
  *
  * ==Deliberately not an enum, and that is a measurement rather than laziness==
  *
  * `spec/swagger.v1.json` declares this parameter as `type: string` with the description "subject of the quota" and
  * '''no `enum`''' — unlike, say, `Team.permission`, which the same spec does enumerate. Forgejo's quota subjects are
  * colon-separated paths such as `size:all` and `size:repos:public`, but the pinned spec names none of them, and
  * `docs/HAZARDS.md` is explicit that a vocabulary the spec does not state is a vocabulary this library must not
  * invent. An enum written from Forgejo's source would be a promise about a set that release notes can change and that
  * no artefact in this repository can check.
  *
  * So this is a validated string. It exists as a type rather than a bare `String` for the same reason
  * [[com.worxbend.codeberg4s.issues.LabelName]] does: it is a wire-sensitive value a caller supplies, and rejecting a
  * blank or control-character-bearing one here is better than sending a request that produces an unexplained `422`.
  *
  * A subject the instance does not know comes back as a `422`, which is the instance's judgement and the right place
  * for it. [[QuotaRule.subjects]] carries the subjects an instance actually applies, so a caller who wants to ask about
  * a real one can read [[OrganizationQuotaApi.get]] first and convert a value from there.
  */
opaque type QuotaSubject = String

object QuotaSubject:

  /** The field name a rejected value is reported under. */
  private val Field: String = "quotaSubject"

  /** Parses a quota subject.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value and a value containing a control character — the
    * latter because this value becomes a query parameter, and a control character there corrupts the request rather
    * than producing a clean rejection.
    *
    * '''No structure is imposed.''' A colon count, a known prefix, a maximum depth: all of them would be rules this
    * library invented, and any one of them would refuse a subject a later Forgejo adds. See the type note.
    *
    * @return
    *   the trimmed subject, or a [[ValidationError]] on the `"quotaSubject"` field
    */
  def from(value: String): Either[ValidationError, QuotaSubject] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError(Field, "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(Field, "must not contain a control character"))
    else Right(trimmed)

  extension (subject: QuotaSubject)

    /** The subject as a string, ready to be sent as the `subject` parameter. */
    def value: String = subject
