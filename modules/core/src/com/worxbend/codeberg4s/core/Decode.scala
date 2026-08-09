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

  /** Whether the successful body this instance reads is credential material.
    *
    * `false` for every endpoint but a handful, and that default is deliberate: when a payload does not decode,
    * [[ApiPipeline]] puts a bounded excerpt of it into [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]],
    * because an excerpt is what makes such a failure diagnosable at all.
    *
    * A few endpoints answer `2xx` with a body that '''is''' a live secret — the `201` of a token creation carries a
    * usable access token, an OAuth2 application registration carries a client secret, and a runner registration carries
    * the credential the runner authenticates with. On those, the excerpt that helps everywhere else is a credential
    * disclosure into whatever the application logs. Setting this to `true` tells the pipeline to substitute a fixed
    * placeholder — see [[ApiPipeline.redactedSnippet]] — which reports that a body arrived and how big it was without
    * reproducing any of it.
    *
    * Sensitivity is a property of the '''endpoint's response''', not of the decoded type: a listing that names tokens
    * without carrying their material keeps its excerpt, and only the response that carries material is marked.
    */
  def sensitive: Boolean = false

/** How an instance declares that the body it reads is a credential. */
object Decode:

  /** `decode`, marked so that a decoding failure reports a placeholder instead of an excerpt of the body.
    *
    * Wrapping rather than requiring an `override` at each definition site is what lets an existing instance — a SAM
    * lambda, or whatever the `client` module composes out of a codec — be marked without changing how it is built. The
    * decoding itself is untouched; only [[Decode.sensitive]] differs.
    */
  def sensitive[A](decode: Decode[A]): Decode[A] =
    new Decode[A]:
      def apply(body: ResponseBody): Either[DecodeFailure, A] = decode(body)

      override def sensitive: Boolean = true
