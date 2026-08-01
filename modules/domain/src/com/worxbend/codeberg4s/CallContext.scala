package com.worxbend.codeberg4s

/** Everything a caller needs to identify one attempted API call after it failed.
  *
  * A `CallContext` is attached to every remote failure so that a log line or a bug report identifies the operation
  * without the caller having to reconstruct it.
  *
  * '''Security contract:''' `uri` is already redacted by the transport before this value is built. It must never
  * contain a token, a password, or a `sudo` parameter. Nothing downstream re-derives a URI from the configuration.
  *
  * @param operation
  *   a stable, greppable operation id such as `"issues.list"`, one per endpoint; it never changes, so it is safe to
  *   alert on
  * @param method
  *   the HTTP method that was used
  * @param uri
  *   the request URI, already redacted
  * @param requestId
  *   the value of the `x-request-id` response header when the instance supplied one
  * @param durationMs
  *   wall-clock duration of the attempt in milliseconds, measured by the transport
  */
final case class CallContext(
    operation: String,
    method: HttpMethod,
    uri: String,
    requestId: Option[String],
    durationMs: Long,
)
