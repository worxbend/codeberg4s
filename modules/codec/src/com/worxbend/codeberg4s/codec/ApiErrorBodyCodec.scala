package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.ApiErrorBody

/** Reads Forgejo's error payload.
  *
  * The shape is measured, not assumed — `docs/HAZARDS.md` §4 captures it live:
  *
  * {{{
  * {"message":"GetUserByName","url":"https://codeberg.org/api/swagger",
  *  "errors":["user redirect does not exist [name: definitely]"]}
  * }}}
  *
  * Three facts drive every decision here:
  *
  *   - `message` is frequently a raw Go symbol (`GetUserByName`) or a raw Go error string, not a sentence. It is
  *     carried verbatim because it is sometimes all there is, but `errors` is the field worth showing a human.
  *   - `errors` is optional and is declared by the spec on exactly one of the four error models, so its absence says
  *     nothing. It also arrives as `[]`, as on `golden/error/404-repo-not-found.json`.
  *   - `url` is the constant `https://codeberg.org/api/swagger` on every captured body. It is parsed for completeness
  *     and is never worth showing.
  */
object ApiErrorBodyCodec:

  /** Parses an error body.
    *
    * '''Total.''' A body that is not JSON, is JSON but not an object, is empty, or is truncated yields
    * [[com.worxbend.codeberg4s.ApiErrorBody.Empty]] rather than a failure. That is the whole point: an error body is
    * already the explanation of a failure, so a second failure while reading it would mask the status code that carried
    * it. Forgejo really does answer some requests with `text/plain` — the golden-fixture manifest records
    * `GET /nodeinfo` returning the bare text `404 page not found` — so this path is exercised in practice, not just in
    * theory.
    *
    * Field-level leniency matches [[JsonFields]]: a `message` of the wrong JSON kind, or blank, is `None`; an `errors`
    * that is `null`, absent, or not an array is `Nil`; array elements that are not strings are dropped.
    *
    * @param body
    *   the raw non-2xx response body, exactly as received
    * @return
    *   what could be understood, never an error
    */
  def parse(body: String): ApiErrorBody =
    Json
      .decode[ApiErrorBody](body)
      .getOrElse(ApiErrorBody.Empty)

  /** The reader behind [[parse]]. Exposed so a caller decoding an error body inside a larger document — a batch
    * endpoint, a test — reuses exactly the same leniency.
    */
  given JsonDecoder[ApiErrorBody] =
    JsonFields.reader: fields =>
      ApiErrorBody(
        message = fields.text("message"),
        url     = fields.text("url"),
        errors  = fields.texts("errors").toList,
      )
