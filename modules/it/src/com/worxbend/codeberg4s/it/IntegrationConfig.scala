package com.worxbend.codeberg4s.it

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.UserAgent
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.retry.RetryPolicy

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/** The client configuration both integration suites are built on.
  *
  * The two suites disagree about exactly one thing — the retry policy. A container on loopback has no reason to be
  * flaky, so the container suite runs with [[com.worxbend.codeberg4s.retry.RetryPolicy.Off]] and a failure there is a
  * real failure rather than something a retry may paper over. The live suite crosses the public internet, so it keeps
  * the default policy. Everything else is shared here so that the two lanes cannot drift apart in a way that makes one
  * of them prove something the other does not.
  *
  * '''Error contract.''' Nothing here can fail: every value it composes is already a validated domain type.
  */
object IntegrationConfig:

  /** How long to wait for a connection. Generous, because a container's first request may follow a cold JIT. */
  val ConnectTimeout: FiniteDuration = 15.seconds

  /** How long to wait for a response body once the request is on the wire. */
  val ReadTimeout: FiniteDuration = 60.seconds

  /** Builds the configuration for one instance.
    *
    * @param baseUri
    *   the API root, `…/api/v1`, of the instance under test
    * @param auth
    *   the credentials to send; [[com.worxbend.codeberg4s.auth.Auth.Anonymous]] is a legitimate choice for read-only
    *   probes
    * @param retry
    *   the retry policy, which is the only thing the two suites configure differently
    */
  def forInstance(baseUri: BaseUri, auth: Auth, retry: RetryPolicy): CodebergConfig =
    CodebergConfig(
      baseUri         = baseUri,
      auth            = auth,
      retry           = retry,
      userAgent       = UserAgent.Default,
      defaultPageSize = PageSize.Default,
      connectTimeout  = ConnectTimeout,
      readTimeout     = ReadTimeout,
    )
