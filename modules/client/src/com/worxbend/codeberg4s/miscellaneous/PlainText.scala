package com.worxbend.codeberg4s.miscellaneous

import com.worxbend.codeberg4s.core.Decode

/** The identity [[com.worxbend.codeberg4s.core.Decode]]: a response body, unchanged.
  *
  * [[com.worxbend.codeberg4s.core.ApiPipeline]] reads every successful response through a `Decode[A]`, and almost every
  * Forgejo endpoint answers JSON, so almost every instance is built by [[com.worxbend.codeberg4s.client.WireDecode]]
  * over a codec. A handful of endpoints are not JSON at all: `GET /signing-key.gpg` returns an armored OpenPGP block,
  * `POST /markdown` and `POST /markdown/raw` return an HTML fragment. Running those through a JSON parser would turn a
  * perfectly good response into [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] — an armored key begins
  * `-----BEGIN`, which is not a JSON document by any reading.
  *
  * So the pipeline does not need a special case; it needs a decoder that parses nothing. That is this, and giving it a
  * name is what stops the next non-JSON endpoint from inventing its own inline lambda.
  *
  * '''Never fails.''' There is nothing to fail at: any sequence of characters is a valid plain-text body, including an
  * empty one. An endpoint for which an empty body is meaningful — `signing-key.gpg` answers `200` with nothing when the
  * instance signs nothing — interprets that itself, after this decoder has handed the body over.
  *
  * Internal: a caller of this library never names this. It is `private[codeberg4s]` so later endpoint waves can reuse
  * it without widening the published API.
  */
private[codeberg4s] object PlainText:

  /** The body exactly as received: no trimming, no charset guessing, no parsing.
    *
    * The transport has already decoded the bytes into a `String`, so the only thing left to get wrong would be changing
    * them.
    */
  val decoder: Decode[String] =
    (body: String) => Right(body)

  /** The identity decoder followed by `interpret`, for an endpoint whose body is text but whose result is not a
    * `String`.
    *
    * Composition rather than a second decoder per endpoint, so the "parse nothing" decision stays stated in exactly one
    * place. `interpret` must be total: this is the success path, and a body that reached it is a body the instance
    * answered `2xx` with.
    *
    * @param interpret
    *   turns the raw body into the domain value, for example an armored key into
    *   [[com.worxbend.codeberg4s.miscellaneous.SigningKey]]
    */
  def decodedAs[A](interpret: String => A): Decode[A] =
    (body: String) => decoder(body).map(interpret)
