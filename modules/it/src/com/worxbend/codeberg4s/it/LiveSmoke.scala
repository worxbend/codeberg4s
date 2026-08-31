package com.worxbend.codeberg4s.it

import com.worxbend.codeberg4s.auth.{ApiToken, Auth}
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.{BaseUri, CodebergConfig}

/** The switch and the configuration for the opt-in smoke suite against the public `codeberg.org`.
  *
  * Talking to a real, shared instance is opt-in because it costs someone else's rate-limit budget and because a red
  * build should mean this library is broken, not that a forge was being upgraded. `docs/HAZARDS.md` §6 measured the
  * anonymous allowance at 2000 requests per ten minutes, and notes that failed requests consume it too, so the suite is
  * kept to a handful of `GET`s.
  *
  * '''Read-only, always.''' Nothing that reaches codeberg.org from this module may be a `POST`, `PATCH`, `PUT` or
  * `DELETE`. Mutating behaviour is proved against the container, which is disposable; proving it here would leave
  * litter in someone else's forge.
  *
  * '''Error contract.''' Nothing here fails. [[enabled]] reads an environment variable and answers `false` when it is
  * absent or holds anything other than `1`; [[auth]] falls back to [[com.worxbend.codeberg4s.auth.Auth.Anonymous]] when
  * no token is supplied or when the supplied one is not a well-formed token, because a malformed token is not a reason
  * to fail a suite that is entirely capable of running without one.
  */
object LiveSmoke:

  /** The environment variable that turns the live suite on. Set it to `1`. */
  val EnableVariable: String = "CODEBERG_IT"

  /** The environment variable holding an optional personal access token, used only to widen the rate limit. */
  val TokenVariable: String = "CODEBERG_IT_TOKEN"

  /** What munit prints for every test the suite skips, so an absent variable is visible rather than silent. */
  val SkipMessage: String =
    s"skipped: set $EnableVariable=1 to run the read-only smoke suite against https://codeberg.org"

  /** Whether the operator asked for the live suite. `false` for any value other than `1`, and for an absent variable. */
  def enabled: Boolean =
    sys.env.get(EnableVariable).exists(value => value.trim.equalsIgnoreCase("1"))

  /** The credentials to use: the operator's token when it is present and well formed, anonymous otherwise. */
  def auth: Auth =
    sys.env
      .get(TokenVariable)
      .flatMap(value => ApiToken.from(value).toOption)
      .fold(Auth.Anonymous)(token => Auth.Token(token))

  /** The client configuration for `https://codeberg.org/api/v1`, retrying as an ordinary caller would. */
  def config: CodebergConfig =
    IntegrationConfig.forInstance(BaseUri.Codeberg, auth, RetryPolicy.Default)
