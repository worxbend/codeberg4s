package com.worxbend.codeberg4s

/** Why a request produced no usable HTTP response.
  *
  * The transport adapter classifies the exception it caught into one of these cases; anything it cannot classify
  * becomes [[TransportCause.Unknown]] rather than being dropped. A status code — including `5xx` — is never a transport
  * cause: that is [[CodebergError.Api]].
  *
  * All but one of these mean nothing arrived at all. [[TransportCause.ResponseTooLarge]] is the exception: a response
  * did begin to arrive, and reading it was abandoned once it passed the bound in [[CodebergConfig]]. It is here rather
  * than under [[CodebergError.Api]] because the status is not what went wrong and the body was never completed, so
  * there is nothing to hand a status-mapping decision.
  *
  * Each case carries a short `detail` taken from the underlying exception message. Details are for humans; callers
  * branch on the case, not on the text.
  */
enum TransportCause:

  /** The connection could not be established or was reset before a response arrived. */
  case ConnectionFailed(detail: String)

  /** The connect or read timeout configured in [[CodebergConfig]] elapsed. */
  case Timeout(detail: String)

  /** The TLS handshake or certificate validation failed. */
  case Tls(detail: String)

  /** The host name could not be resolved. */
  case Dns(detail: String)

  /** The calling thread was interrupted while the request was in flight. */
  case Interrupted(detail: String)

  /** The response body passed the byte bound configured in [[CodebergConfig]] and was abandoned part-read.
    *
    * A case of its own rather than an [[Unknown]] because retrying differs: an unclassified failure may be transient
    * and is attempted again, whereas an instance that answered with too many bytes will answer with too many bytes
    * again. Repeating it would download the oversized body once per attempt — the opposite of what a bound is for — so
    * this case is excluded from retrying.
    *
    * Which bound was passed depends on the operation: [[CodebergConfig.maxDownloadBodyBytes]] for the two archive
    * downloads under `client.repos.actions`, [[CodebergConfig.maxResponseBodyBytes]] for everything else.
    */
  case ResponseTooLarge(detail: String)

  /** The transport failed in a way this library does not classify. */
  case Unknown(detail: String)

  /** A short, secret-free rendering suitable for embedding in [[CodebergError.describe]]. */
  def describe: String =
    this match
      case ConnectionFailed(detail) => s"connection failed ($detail)"
      case Timeout(detail)          => s"timed out ($detail)"
      case Tls(detail)              => s"TLS failure ($detail)"
      case Dns(detail)              => s"name resolution failed ($detail)"
      case Interrupted(detail)      => s"interrupted ($detail)"
      case ResponseTooLarge(detail) => s"response body too large ($detail)"
      case Unknown(detail)          => s"unclassified transport failure ($detail)"
