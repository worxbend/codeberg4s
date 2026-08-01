package com.worxbend.codeberg4s

/** The HTTP methods this library issues.
  *
  * The domain deliberately owns this type rather than borrowing one from a transport library: nothing below the
  * transport adapter should depend on sttp's `Method`.
  */
enum HttpMethod:
  case Get, Head, Post, Put, Patch, Delete

  /** The uppercase name to put on the wire, for example `"GET"`. */
  def wireName: String =
    this match
      case Get    => "GET"
      case Head   => "HEAD"
      case Post   => "POST"
      case Put    => "PUT"
      case Patch  => "PATCH"
      case Delete => "DELETE"

  /** Whether the method is *safe* in the RFC 9110 sense: it has no intended side effect on the server.
    *
    * Only safe methods are retried automatically; a caller who wants a mutating request retried has to opt in per call,
    * because Forgejo does not offer idempotency keys.
    */
  def isSafe: Boolean =
    this match
      case Get  | Head                 => true
      case Post | Put | Patch | Delete => false
