package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.HttpMethod

/** One API call, described independently of any HTTP library.
  *
  * Every endpoint in the library builds one of these, so authentication, the base URI and pagination cannot be
  * forgotten at a call site: the transport adapter is the only place that knows how to turn a `CodebergRequest` into a
  * real request, and it is the only place that adds credentials.
  *
  * '''Security contract:''' `headers` never contains an `Authorization` header. Credentials are applied by the
  * transport from [[com.worxbend.codeberg4s.auth.Auth]], so no credential can reach a log line, a
  * [[com.worxbend.codeberg4s.CallContext]] or an error payload through this type.
  *
  * @param operation
  *   the stable, greppable operation id, for example `"repos.get"`; it is copied into every failure
  * @param method
  *   the HTTP method; [[com.worxbend.codeberg4s.HttpMethod.isSafe]] decides whether the retry engine may repeat the
  *   call
  * @param path
  *   unencoded path segments appended to the base URI, in order, each already validated by an opaque domain type.
  *   Percent-encoding happens at the boundary, never here
  * @param query
  *   query parameters in the order they should appear; a key may repeat, which is why this is a list and not a map
  * @param headers
  *   extra headers, never including `Authorization`
  * @param body
  *   the payload, absent for a request that carries none
  */
final case class CodebergRequest(
    operation: String,
    method: HttpMethod,
    path: List[String],
    query: List[(String, String)],
    headers: List[(String, String)],
    body: Option[RequestBody],
)
