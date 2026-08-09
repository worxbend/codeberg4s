package com.worxbend.codeberg4s

/** Validation shared by every media type this library writes into a header.
  *
  * This is a security boundary, not a convenience. A media type reaches the wire as the value of a `Content-Type`
  * header — for an upload, the header of the multipart part itself — and an HTTP header value ends at the first
  * carriage return or newline. A value carrying one would close that header early and let everything after it be read
  * as headers of the caller's choosing, or as the start of a part the caller was never offered. That is header
  * injection, and the only reliable place to stop it is before the value becomes bytes.
  *
  * '''It lives in the root package for the reason [[PathSegment]] does''': a rule that only one package can reach gets
  * copied into the packages that cannot, and copies drift. [[com.worxbend.codeberg4s.issues.UploadAttachment]] and
  * [[com.worxbend.codeberg4s.repositories.publishing.UploadAsset]] both call [[from]], which is the check that gives a
  * caller a named [[ValidationError]] before any request exists. The sttp adapter calls [[isSafe]] a second time on the
  * exact string it is about to write. The two are not redundant: the domain check is the one a caller can act on, and
  * the transport check is the last point at which the value is still a Scala `String` rather than wire bytes.
  */
private[codeberg4s] object ContentType:

  /** Whether `value` can be written verbatim as a header value.
    *
    * True when `value` holds at least one non-whitespace character and no control character. Carriage return and
    * newline are control characters, so the injection case falls out of the general rule rather than needing a clause
    * of its own.
    */
  def isSafe(value: String): Boolean = !value.isBlank && !value.exists(_.isControl)

  /** Trims `value` and accepts it only when the result is safe to write as a header value.
    *
    * The trim happens first, so a media type that arrived with trailing whitespace — including the newline a caller
    * gets from reading a line out of a file — is accepted as its trimmed form rather than refused. A control character
    * anywhere the trim does not reach is a rejection, which is the case that matters: `text/plain\r\nX-Injected: 1` has
    * the injection in the middle, where no amount of trimming removes it.
    *
    * @param field
    *   the field name to report in a [[ValidationError]], for example `"mediaType"`
    * @return
    *   the trimmed media type, or a [[ValidationError]] naming `field`
    */
  def from(field: String, value: String): Either[ValidationError, String] =
    val trimmed = value.trim
    // Written as "accept, then explain" rather than as a chain of rejections,
    // because [[isSafe]] is the whole rule and the transport applies exactly
    // it. The two branches below only choose which half of it was broken.
    if isSafe(trimmed) then Right(trimmed)
    else if trimmed.isEmpty then Left(ValidationError(field, "must not be blank"))
    else Left(ValidationError(field, "must not contain a control character"))
