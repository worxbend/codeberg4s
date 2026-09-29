package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.retry.RetryPolicy

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

/** Everything a client needs to talk to one Forgejo deployment.
  *
  * Built once near the client's construction and passed by constructor from there on. Every field naming a domain
  * concept — the base URI, the credentials, the page size, the user agent — is a validated type rather than a raw
  * primitive, so a misconfigured client fails at construction instead of on the first call.
  *
  * ==Invariants==
  *
  * The two timeouts and the two response-body bounds are quantities rather than domain concepts and are carried as
  * plain durations and numbers. A configuration is well-formed when all of the following hold:
  *
  *   - `connectTimeout > 0` and `readTimeout > 0` — a zero or negative timeout is a call that fails before it starts;
  *   - `maxResponseBodyBytes >= 1` and `maxDownloadBodyBytes >= 1` — a bound of zero bytes rejects every response;
  *   - `retry` satisfies [[retry.RetryPolicy.validate]].
  *
  * '''Neither the primary constructor, [[CodebergConfig.apply]] with one argument, nor `copy` checks them''':
  * `CodebergConfig(auth).copy(readTimeout = -1.second)` compiles and constructs. That is deliberate — the constructor
  * stays a plain case-class constructor so the one-argument form and a `copy` of it read the way every guide writes
  * them — but it means such a value is trusted, not verified. [[CodebergConfig.of]] is the full constructor with the
  * check in front, and [[validate]] runs the check on a configuration already in hand; either is the right call for
  * values that came from a file or an environment.
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
  *   the size of `CodebergClient.firstPage`, the ready-made first window; every listing takes explicit
  *   [[paging.PageParams]], so this is the only place the value is read
  * @param connectTimeout
  *   how long to wait for the connection to be established
  * @param readTimeout
  *   how long the whole response may take once the request has been sent — status line, headers and body together. The
  *   transport races the exchange against this deadline, so a body that stalls part-way fails with
  *   `TransportCause.Timeout` rather than hanging. The body is additionally bounded by size, through
  *   [[maxResponseBodyBytes]] and [[maxDownloadBodyBytes]], rather than by time
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
):

  /** Checks the invariants listed in the class note, which the constructor did not.
    *
    * The first violated invariant is reported, on the field that violates it — `"connectTimeout"`, `"readTimeout"`,
    * `"maxResponseBodyBytes"`, `"maxDownloadBodyBytes"`, or one of the [[retry.RetryPolicy.validate]] fields — so a
    * configuration read from a file fails where it was misread rather than on the first call.
    *
    * @return
    *   this configuration unchanged, or a [[ValidationError]] on the offending field
    */
  def validate: Either[ValidationError, this.type] =
    if connectTimeout <= Duration.Zero then Left(ValidationError("connectTimeout", "must be positive"))
    else if readTimeout <= Duration.Zero then Left(ValidationError("readTimeout", "must be positive"))
    else if maxResponseBodyBytes < CodebergConfig.MinBodyBytes then
      Left(ValidationError("maxResponseBodyBytes", s"must be at least ${CodebergConfig.MinBodyBytes}"))
    else if maxDownloadBodyBytes < CodebergConfig.MinBodyBytes then
      Left(ValidationError("maxDownloadBodyBytes", s"must be at least ${CodebergConfig.MinBodyBytes}"))
    else retry.validate.map(_ => this)

object CodebergConfig:

  /** The smallest body bound that lets any response through at all. */
  private val MinBodyBytes: Long = 1L

  /** Time allowed to establish a connection when using the Codeberg defaults. */
  val DefaultConnectTimeout: FiniteDuration = 10.seconds

  /** Time allowed for the whole response — headers and body — once the request has been sent, when using the Codeberg
    * defaults.
    */
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

  /** Builds a configuration and checks the invariants listed in the class note, which the constructor does not.
    *
    * Same parameters as the constructor, in the same order, so a call site can switch between the two by changing the
    * name; the difference is that this one returns a `Left` for a non-positive timeout, a body bound below one byte or
    * a retry policy that fails its own [[retry.RetryPolicy.validate]], instead of a client whose every call fails. See
    * [[CodebergConfig.validate]] for what is reported.
    *
    * @return
    *   the configuration, or a [[ValidationError]] on the first offending field
    */
  def of(
      baseUri: BaseUri,
      auth: Auth,
      retry: RetryPolicy,
      userAgent: UserAgent,
      defaultPageSize: PageSize,
      connectTimeout: FiniteDuration,
      readTimeout: FiniteDuration,
      maxResponseBodyBytes: Long,
      maxDownloadBodyBytes: Long,
  ): Either[ValidationError, CodebergConfig] =
    new CodebergConfig(
      baseUri              = baseUri,
      auth                 = auth,
      retry                = retry,
      userAgent            = userAgent,
      defaultPageSize      = defaultPageSize,
      connectTimeout       = connectTimeout,
      readTimeout          = readTimeout,
      maxResponseBodyBytes = maxResponseBodyBytes,
      maxDownloadBodyBytes = maxDownloadBodyBytes,
    ).validate
