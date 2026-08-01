package com.worxbend.codeberg4s

/** Why a request never produced an HTTP response.
  *
  * The transport adapter classifies the exception it caught into one of these cases; anything it cannot classify
  * becomes [[TransportCause.Unknown]] rather than being dropped. A status code — including `5xx` — is never a transport
  * cause: that is [[CodebergError.Api]].
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
      case Unknown(detail)          => s"unclassified transport failure ($detail)"
