package com.worxbend.codeberg4s.core

/** Turns a response body into a value.
  *
  * Core never imports a JSON library; the `codec` module supplies instances of this trait, so the choice of JSON
  * library is invisible above the boundary and replaceable without touching a single use case.
  *
  * An instance must be total: a malformed, truncated or unexpected payload returns a [[DecodeFailure]], and no
  * implementation lets a codec exception escape.
  *
  * @tparam A
  *   the decoded type, usually a wire DTO rather than a domain model
  */
trait Decode[A]:

  /** Decodes `body`, or explains where and why it could not be decoded. */
  def apply(body: String): Either[DecodeFailure, A]
