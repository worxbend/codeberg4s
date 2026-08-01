package com.worxbend.codeberg4s.core

/** The payload of a request, as far as core is concerned.
  *
  * Core never imports a JSON library, so a body reaches it already serialised: the `codec` module renders a wire DTO
  * into a string and hands it over. The transport adapter is what turns [[RequestBody.Json]] into bytes and adds the
  * `Content-Type` header.
  */
enum RequestBody:

  /** A serialised JSON document, sent with `Content-Type: application/json`. */
  case Json(value: String)

  /** A request with a body that is deliberately empty, as some Forgejo `PUT` endpoints require. */
  case Empty
