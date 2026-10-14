package com.worxbend.codeberg4s

/** Why a request produced no usable HTTP response.
  *
  * The transport adapter classifies the exception it caught into one of these cases; anything it cannot classify
  * becomes [[TransportCause.Unknown]] rather than being dropped. A status code — including `5xx` — is never a transport
  * cause: that is [[CodebergError.Api]].
  *
  * None proves that the server did not receive or apply a request. A read timeout or reset may follow headers or a
  * partial body. [[TransportCause.ResponseTooLarge]] specifically means the decoded body exceeded its configured bound.
  * These belong here rather than under [[CodebergError.Api]] because no complete usable response was available to
  * classify by status; mutation replay must follow the operation's policy, not this diagnostic text.
  *
  * Each case carries a short `detail` taken from the underlying exception message. Details are for humans; callers
  * branch on the case, not on the text.
  */
enum TransportCause:

  /** The connection could not be established or was reset before a complete usable response arrived. */
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
    * A case of its own rather than an [[Unknown]] because the two say different things: an unclassified failure is one
    * the adapter could not name, whereas an instance that answered with too many bytes will answer with too many bytes
    * again. Neither is retried — repeating this one would download the oversized body once per attempt, the opposite of
    * what a bound is for — but a caller reading the failure deserves to know which of the two it was.
    *
    * Which bound was passed depends on the operation: [[CodebergConfig.maxDownloadBodyBytes]] for the two archive
    * downloads under `client.repos.actions`, [[CodebergConfig.maxResponseBodyBytes]] for everything else.
    */
  case ResponseTooLarge(detail: String)

  /** The transport failed in a way this library does not classify. */
  case Unknown(detail: String)

  /** A human-readable category and detail; sanitization belongs to the client diagnostic boundary. */
  def describe: String =
    this match
      case ConnectionFailed(detail) => s"connection failed ($detail)"
      case Timeout(detail)          => s"timed out ($detail)"
      case Tls(detail)              => s"TLS failure ($detail)"
      case Dns(detail)              => s"name resolution failed ($detail)"
      case Interrupted(detail)      => s"interrupted ($detail)"
      case ResponseTooLarge(detail) => s"response body too large ($detail)"
      case Unknown(detail)          => s"unclassified transport failure ($detail)"
