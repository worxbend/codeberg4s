package com.worxbend.codeberg4s

/** A parsed Forgejo error payload.
  *
  * Forgejo answers a failed request with `{"message": ..., "url": ...}` and occasionally adds `errors: [...]`. Every
  * field is optional in practice even where the spec claims otherwise, so decoding an error body never fails: an
  * unparseable payload becomes [[ApiErrorBody.Empty]] rather than a second, nested failure.
  *
  * @param message
  *   the server-supplied explanation, when present
  * @param url
  *   the documentation URL Forgejo points at, when present
  * @param errors
  *   per-field problems reported by validation endpoints; empty when the server sent none
  */
final case class ApiErrorBody(message: Option[String], url: Option[String], errors: List[String])

object ApiErrorBody:

  /** The payload used when the server sent no body, or a body that could not be understood. */
  val Empty: ApiErrorBody = ApiErrorBody(None, None, Nil)
