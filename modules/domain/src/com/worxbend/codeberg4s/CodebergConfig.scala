package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.retry.RetryPolicy

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/** Everything a client needs to talk to one Forgejo deployment.
  *
  * Built once near the client's construction and passed by constructor from there on. Every field is a validated domain
  * type rather than a raw primitive, so a misconfigured client fails at construction instead of on the first call.
  *
  * `toString` is safe to log: the credential types inside [[auth.Auth]] redact themselves.
  *
  * @param baseUri
  *   the API root; defaults to [[BaseUri.Codeberg]] but must be configurable for self-hosted instances
  * @param auth
  *   how requests are authenticated
  * @param retry
  *   the retry policy applied to safe methods; see [[retry.RetryPolicy]]
  * @param userAgent
  *   the `User-Agent` header sent with every request
  * @param defaultPageSize
  *   the page size used when a caller does not supply [[paging.PageParams]]
  * @param connectTimeout
  *   how long to wait for the connection to be established
  * @param readTimeout
  *   how long to wait for the response once the request has been sent
  */
final case class CodebergConfig(
    baseUri: BaseUri,
    auth: Auth,
    retry: RetryPolicy,
    userAgent: UserAgent,
    defaultPageSize: PageSize,
    connectTimeout: FiniteDuration,
    readTimeout: FiniteDuration,
)

object CodebergConfig:

  /** Time allowed to establish a connection when using the Codeberg defaults. */
  val DefaultConnectTimeout: FiniteDuration = 10.seconds

  /** Time allowed for a response body once the request has been sent, when using the Codeberg defaults. */
  val DefaultReadTimeout: FiniteDuration = 30.seconds

  /** Codeberg defaults for everything except authentication.
    *
    * Uses [[BaseUri.Codeberg]], [[retry.RetryPolicy.Default]], [[UserAgent.Default]], [[paging.PageSize.Default]],
    * [[DefaultConnectTimeout]] and [[DefaultReadTimeout]]. Copy the result to change one field.
    */
  def apply(auth: Auth): CodebergConfig =
    new CodebergConfig(
      baseUri         = BaseUri.Codeberg,
      auth            = auth,
      retry           = RetryPolicy.Default,
      userAgent       = UserAgent.Default,
      defaultPageSize = PageSize.Default,
      connectTimeout  = DefaultConnectTimeout,
      readTimeout     = DefaultReadTimeout,
    )
