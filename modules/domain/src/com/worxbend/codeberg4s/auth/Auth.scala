package com.worxbend.codeberg4s.auth

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

  /** HTTP basic credentials, sent as `Authorization: Basic <base64>`. Supported for older self-hosted instances. */
  case Basic(username: String, password: Password)
