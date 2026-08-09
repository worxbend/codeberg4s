package com.worxbend.codeberg4s.core

/** Turns a response body into a value.
  *
  * Core never imports a JSON library; the `codec` module supplies instances of this trait, so the choice of JSON
  * library is invisible above the boundary and replaceable without touching a single use case. The argument is a
  * [[ResponseBody]] — bytes and a declared charset, both from the standard library — rather than a `String`, so that a
  * parser which reads bytes reads the ones that arrived instead of a re-encoding of a decoding of them. An instance
  * that genuinely wants text asks the body for it; see [[ResponseBody.text]].
  *
  * An instance must be total: a malformed, truncated or unexpected payload returns a [[DecodeFailure]], and no
  * implementation lets a codec exception escape.
  *
  * @tparam A
  *   the decoded type, usually a wire DTO rather than a domain model
  */
trait Decode[A]:

  /** Decodes `body`, or explains where and why it could not be decoded. */
  def apply(body: ResponseBody): Either[DecodeFailure, A]
