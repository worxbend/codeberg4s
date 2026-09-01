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
  * [[com.worxbend.codeberg4s.CallContext]] or an error payload through this type. The rule is enforced and not merely
  * documented: the transport adapter drops an `Authorization` or `Proxy-Authorization` entry from this list before it
  * applies the configured credential, so a request built with one still goes out authenticated as `Auth` says.
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

/** The handful of request shapes every endpoint in the library is built from.
  *
  * Before these existed, each of the twenty-odd API companions carried its own private copy of the same six-line
  * constructor call, under whichever name that file happened to pick — the body-less mutation alone was spelled
  * `remove`, `bare` and `mutate` in three different packages. That is the duplication `docs/LEDGER.md` records as a
  * review-blocking defect, and it costs more than tidiness: a copy that quietly forgot to leave `headers` empty would
  * be indistinguishable from one that did not until a credential turned up in a log line.
  *
  * Building every request here, once, is what makes the security contract of [[CodebergRequest]] checkable rather than
  * merely documented. None of these builders takes headers, so no call site anywhere in the library can set one, and
  * therefore none can set an `Authorization` one; credentials are attached by the transport, from
  * [[com.worxbend.codeberg4s.auth.Auth]], and nowhere else.
  *
  * Internal to the library: these are the vocabulary the endpoint modules share, not part of the public API.
  */
object CodebergRequest:

  /** A `GET`, with a query that may be empty. */
  private[codeberg4s] def read(
      operation: String,
      path: List[String],
      query: List[(String, String)],
  ): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Get,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = None,
    )

  /** A mutating call carrying a JSON body. */
  private[codeberg4s] def write(
      operation: String,
      method: HttpMethod,
      path: List[String],
      body: String,
  ): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Json(body)),
    )

  /** A mutating call whose whole meaning is its method and its path.
    *
    * Forgejo's membership, subscription, block and team-assignment routes take no body at all: what is being said is
    * said by the path. Sending `{}` on the chance the instance prefers it would be guesswork, and `docs/HAZARDS.md` §4
    * shows Forgejo answering `400` to bodies it did not expect.
    */
  private[codeberg4s] def bodiless(
      operation: String,
      method: HttpMethod,
      path: List[String],
      query: List[(String, String)] = Nil,
  ): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = None,
    )

  /** A mutating call carrying a deliberately empty body.
    *
    * Deliberately not [[bodiless]], which sends no body at all: these routes are among the Forgejo endpoints
    * [[RequestBody.Empty]] exists for, and the difference — a zero-length body with a `Content-Length: 0` header,
    * against no body and no header — is visible to the server. Keeping the two under different names keeps the choice
    * from being made by accident.
    */
  private[codeberg4s] def empty(operation: String, method: HttpMethod, path: List[String]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Empty),
    )

  /** A `DELETE` with no body — the ordinary shape, where what to remove is named in the path. */
  private[codeberg4s] def remove(operation: String, path: List[String]): CodebergRequest =
    bodiless(operation, HttpMethod.Delete, path)

  /** A `DELETE` that carries a JSON body, for the routes where what to remove is not in the URL.
    *
    * Reactions, blocks, dependencies, label removals and `DELETE /user/emails` all need this: the thing being removed
    * is named in the payload and nowhere else, so there is no other way to issue the call. A body on a `DELETE` is
    * unusual — RFC 9110 permits it and defines no semantics for it, and Forgejo defines its own — which is why this is
    * a separate builder rather than an optional argument on [[remove]]: the oddity stays visible at the call sites that
    * need it.
    */
  private[codeberg4s] def removeWithBody(operation: String, path: List[String], body: String): CodebergRequest =
    write(operation, HttpMethod.Delete, path, body)

  /** A `POST` carrying a non-JSON body and a query, which the attachment and asset uploads need. */
  private[codeberg4s] def upload(
      operation: String,
      path: List[String],
      query: List[(String, String)],
      body: RequestBody,
  ): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Post,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = Some(body),
    )
