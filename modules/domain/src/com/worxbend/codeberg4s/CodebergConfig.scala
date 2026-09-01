package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.retry.RetryPolicy

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Everything a client needs to talk to one Forgejo deployment.
  *
  * Built once near the client's construction and passed by constructor from there on. Every field naming a domain
  * concept — the base URI, the credentials, the page size, the user agent — is a validated type rather than a raw
  * primitive, so a misconfigured client fails at construction instead of on the first call. The two timeouts and the
  * two response-body bounds are quantities rather than domain concepts and are carried as they are; a negative or zero
  * value is not rejected here, and would make every call fail.
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
  * @param maxResponseBodyBytes
  *   the most bytes a textual response may carry before the call fails with [[TransportCause.ResponseTooLarge]]; see
  *   [[DefaultMaxResponseBodyBytes]]
  * @param maxDownloadBodyBytes
  *   the same bound for the two archive downloads under `client.repos.actions`, which is larger for the reason
  *   [[DefaultMaxDownloadBodyBytes]] gives
  */
final case class CodebergConfig(
    baseUri: BaseUri,
    auth: Auth,
    retry: RetryPolicy,
    userAgent: UserAgent,
    defaultPageSize: PageSize,
    connectTimeout: FiniteDuration,
    readTimeout: FiniteDuration,
    maxResponseBodyBytes: Long,
    maxDownloadBodyBytes: Long,
)

object CodebergConfig:

  /** Time allowed to establish a connection when using the Codeberg defaults. */
  val DefaultConnectTimeout: FiniteDuration = 10.seconds

  /** Time allowed for a response body once the request has been sent, when using the Codeberg defaults. */
  val DefaultReadTimeout: FiniteDuration = 30.seconds

  /** 16 MiB — the most a textual response may carry before the call is abandoned.
    *
    * This library reads a whole response into memory; it does not stream. Without a bound, the only thing standing
    * between a misbehaving or hostile instance and the client's heap is how long the caller is prepared to wait, so
    * every request carries this one.
    *
    * The number is derived rather than picked. The largest legitimate JSON body Forgejo produces is a file-contents
    * response, whose payload is a repository blob base64-encoded — and `GET /api/v1/settings/api` reports
    * `default_max_blob_size` as `10485760`, 10 MiB (`docs/HAZARDS.md` §5 records the capture). Base64 costs four bytes
    * per three, so 10 MiB of blob reaches roughly 13.4 MiB on the wire, and 16 MiB clears that with room for the
    * surrounding fields.
    *
    * That number is per-instance configuration, not a protocol constant. A client talking to a self-hosted Forgejo that
    * raises it should read the instance's own value back from [[miscellaneous.ServerApiSettings.maxBlobSizeBytes]] and
    * raise this setting to match.
    */
  val DefaultMaxResponseBodyBytes: Long = 16L * 1024 * 1024

  /** 50 MiB — the same bound for the two archive downloads, deliberately larger than [[DefaultMaxResponseBodyBytes]].
    *
    * `client.repos.actions.downloadArtifact` and `downloadRunLogs` fetch a CI artifact or a run's logs as a ZIP. A ZIP
    * is not a JSON document bounded by `default_max_blob_size`; it is whatever a workflow uploaded, so the reasoning
    * behind the textual bound says nothing about it and a shared number would have had to be wrong for one of the two —
    * either small enough to reject ordinary artifacts, or large enough to make the bound on JSON meaningless.
    *
    * 50 MiB is where the project already drew this line: `SECURITY.md` and `docs/ROADMAP.md` both record "attachment
    * streaming above 50 MB" as out of scope for v1, which is to say that archives above that size are the case this
    * library does not undertake to serve. The default now enforces what those documents describe instead of leaving it
    * to the heap. Raise it if you download bigger artifacts and have the memory for them.
    */
  val DefaultMaxDownloadBodyBytes: Long = 50L * 1024 * 1024

  /** Codeberg defaults for everything except authentication.
    *
    * Uses [[BaseUri.Codeberg]], [[retry.RetryPolicy.Default]], [[UserAgent.Default]], [[paging.PageSize.Default]],
    * [[DefaultConnectTimeout]], [[DefaultReadTimeout]], [[DefaultMaxResponseBodyBytes]] and
    * [[DefaultMaxDownloadBodyBytes]]. Copy the result to change one field.
    */
  def apply(auth: Auth): CodebergConfig =
    new CodebergConfig(
      baseUri              = BaseUri.Codeberg,
      auth                 = auth,
      retry                = RetryPolicy.Default,
      userAgent            = UserAgent.Default,
      defaultPageSize      = PageSize.Default,
      connectTimeout       = DefaultConnectTimeout,
      readTimeout          = DefaultReadTimeout,
      maxResponseBodyBytes = DefaultMaxResponseBodyBytes,
      maxDownloadBodyBytes = DefaultMaxDownloadBodyBytes,
    )
