package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.RequestBody

/** How the five API classes of this package build a [[com.worxbend.codeberg4s.core.CodebergRequest]].
  *
  * Five classes issuing thirty-eight requests would otherwise carry five copies of the same four constructors, and a
  * copy that quietly forgot to leave `headers` empty would be indistinguishable from one that did not until a
  * credential turned up in a log. Building them here, once, is also what makes the security contract of
  * [[com.worxbend.codeberg4s.core.CodebergRequest]] checkable: nothing in this package ever sets a header, so nothing
  * in it can set an `Authorization` one.
  *
  * '''Every path in this group starts with `user`''', because every one of these endpoints addresses whoever the
  * configured credentials are. [[AccountRequests.path]] is that prefix written down once, so no endpoint can
  * accidentally reach the `/users/{username}` family — which is a different API with a different security posture, and
  * which [[com.worxbend.codeberg4s.users.UserApi]] owns.
  */
private[account] object AccountRequests:

  /** The first segment every request in this group shares. */
  val Root: String = "user"

  /** The path `Root` followed by `rest`. */
  def path(rest: String*): List[String] =
    Root :: rest.toList

  /** A `GET` with no body and no extra headers. */
  def read(operation: String, path: List[String], query: List[(String, String)]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Get,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = None,
    )

  /** A mutating request carrying a JSON body. */
  def write(operation: String, method: HttpMethod, path: List[String], body: String): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Json(body)),
    )

  /** A `DELETE` with no body. */
  def remove(operation: String, path: List[String]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Delete,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  /** A `DELETE` that carries a JSON body, which `DELETE /user/emails` is the library's only user of.
    *
    * A body on a `DELETE` is unusual and RFC 9110 gives it no defined semantics, but it is what `spec/swagger.v1.json`
    * declares for `userDeleteEmail` — the addresses to remove are named in a `DeleteEmailOption` and nowhere else, so
    * there is no other way to issue the call. It is a separate constructor rather than a flag on [[remove]] so that the
    * oddity is visible at the one call site that needs it.
    */
  def removeWithBody(operation: String, path: List[String], body: String): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Delete,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Json(body)),
    )
