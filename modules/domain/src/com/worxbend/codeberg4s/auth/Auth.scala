package com.worxbend.codeberg4s.auth

import com.worxbend.codeberg4s.ValidationError

/** How this client authenticates against a Forgejo instance.
  *
  * Forgejo advertises several security definitions; this library supports exactly the three below and treats the rest
  * (`SudoParam`, `TOTPHeader`, query-parameter tokens) as unsupported, because they either weaken the redaction
  * guarantee or belong to admin flows that are out of scope.
  *
  * Auth is applied once, in the transport adapter, as a request transformation. Nothing above the transport knows how
  * credentials are carried, and no credential ever reaches a [[com.worxbend.codeberg4s.CallContext]].
  *
  * Every case renders its credentials as `"***"`, so `toString` on an `Auth` — or on a
  * [[com.worxbend.codeberg4s.CodebergConfig]] holding one — is safe to log.
  */
enum Auth:

  /** No credentials. Public endpoints only, and subject to the instance's anonymous rate limit. */
  case Anonymous

  /** A personal access token, sent as `Authorization: token <value>`. The preferred mechanism. */
  case Token(token: ApiToken)

  /** HTTP basic credentials, sent as `Authorization: Basic <base64>`. Supported for older self-hosted instances.
    *
    * '''Built with [[Auth.basic]], not with this constructor''', which is why the constructor is not public. The header
    * is `base64(username + ":" + password)`, and that encoding has exactly one delimiter: a username carrying a `:`
    * would be split there by the receiving instance, so `Basic("a:b", secret)` would authenticate as `a` with a
    * password nobody typed, and an empty username is a header nothing can answer. [[Auth.basic]] refuses both, and
    * refuses a control character for the same reason [[ApiToken.from]] does — it cannot survive header encoding.
    *
    * @param username
    *   the account name, already validated by [[Auth.basic]]
    * @param password
    *   the password, which masks itself
    */
  case Basic private[codeberg4s] (username: String, password: Password)

object Auth:

  /** The field name a rejected username is reported under, stable enough for a caller to branch on. */
  private val UsernameField: String = "username"

  /** Builds HTTP basic credentials, validating the username.
    *
    * Trims surrounding whitespace from the username, because it is routinely read from an environment variable or a
    * file that carries a trailing newline. Rejects an empty or blank username, one containing `:` — the delimiter of
    * the `user:password` pair inside the header, see [[Auth.Basic]] — and one containing a control character, which
    * cannot be put in a header without splitting the request. The password has already been validated by
    * [[Password.from]] and is taken as it is.
    *
    * The returned [[ValidationError]] describes the failure on the `"username"` field and never echoes the rejected
    * input.
    *
    * @return
    *   the credentials, or a [[ValidationError]] on the `"username"` field
    */
  def basic(username: String, password: Password): Either[ValidationError, Auth] =
    val trimmed = username.trim

    if trimmed.isEmpty then Left(ValidationError(UsernameField, "must not be blank"))
    else if trimmed.contains(':') then Left(ValidationError(UsernameField, "must not contain a colon"))
    else if trimmed.exists(_.isControl) then
      Left(ValidationError(UsernameField, "must not contain a control character"))
    else Right(Basic(trimmed, password))
