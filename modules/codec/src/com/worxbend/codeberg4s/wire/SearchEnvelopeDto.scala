package com.worxbend.codeberg4s.wire

import com.worxbend.codeberg4s.codec.JsonDecoder

/** The wrapper Forgejo's search endpoints put around their results.
  *
  * `GET /repos/search` and `GET /users/search` do not return the bare array every other list endpoint returns. They
  * return `{"data": [...], "ok": true}` — verified live in `docs/HAZARDS.md` §3 and captured in
  * `golden/repository/search.json` and `golden/user/user-search.json`. A client that decodes a search response as an
  * array fails at runtime, on an endpoint that looks exactly like the ones that work.
  *
  * `ok` has been `true` on every capture. It is carried rather than asserted on, because nothing documents what a
  * `false` would mean and inventing a meaning for it here would be worse than passing it along.
  *
  * @param ok
  *   the `ok` flag, when present
  * @param data
  *   the results; empty when `data` is absent, `null`, or not an array
  */
final case class SearchEnvelopeDto[A](ok: Option[Boolean], data: Vector[A])

object SearchEnvelopeDto:

  /** Reads a search envelope around any element type that already has a reader.
    *
    * Elements are decoded one at a time, so an element that fails fails the whole envelope — the same contract as a
    * bare list body. Each element is handed straight to the element reader rather than re-parsed, which keeps the
    * failure a plain the JSON parser abort; the trade-off is that [[com.worxbend.codeberg4s.codec.Json.decode]] reports
    * it at the envelope's own path rather than at `$.data[n]`, because the outer parse has already finished by the time
    * an element is built.
    */
  given [A](using JsonDecoder[A]): JsonDecoder[SearchEnvelopeDto[A]] =
    JsonDecoder.objectOfEither: fields =>
      JsonDecoder.all(fields.values("data")).map(decoded => SearchEnvelopeDto(ok = fields.boolean("ok"), data = decoded))
