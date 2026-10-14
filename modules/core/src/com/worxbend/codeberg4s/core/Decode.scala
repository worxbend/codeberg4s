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

  /** Whether this response can contain credential material or echo an unknown request secret.
    *
    * Ordinary decoders retain bounded, configured-credential-scrubbed failure diagnostics. Sensitive decoders instead
    * make [[ApiPipeline]] withhold excerpts, payload-derived paths/reasons, non-2xx error fields, response request ids
    * and free-form transport details. Status, operation, timing, category and Retry-After remain available.
    *
    * Mark token/OAuth/hook credential readers and issuers conservatively, including lists: an upstream field addition
    * or malformed body must not turn a diagnostic into a disclosure. Request-secret writes may independently select the
    * pipeline's sensitive call path even when the successful result contains no secret. Successful values and the
    * response bytes handed to the decoder are unchanged; this is a failure-diagnostic policy, not data scrubbing.
    */
  def sensitive: Boolean = false

/** How an instance declares that the body it reads is a credential. */
object Decode:

  /** `decode`, marked so that the pipeline withholds payload-derived failure diagnostics, not only excerpts.
    *
    * Wrapping rather than requiring an `override` at each definition site is what lets an existing instance — a SAM
    * lambda, or whatever the `client` module composes out of a codec — be marked without changing how it is built. The
    * decoding itself is untouched; only [[Decode.sensitive]] differs.
    */
  def sensitive[A](decode: Decode[A]): Decode[A] =
    new Decode[A]:
      def apply(body: ResponseBody): Either[DecodeFailure, A] = decode(body)

      override def sensitive: Boolean = true
