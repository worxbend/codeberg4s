package com.worxbend.codeberg4s.users.account

/** The path prefix the five API classes of this package share.
  *
  * The request shapes these paths are handed to live in the companion of
  * [[com.worxbend.codeberg4s.core.CodebergRequest]], shared with the whole library, along with the security argument
  * for building them in one place.
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
