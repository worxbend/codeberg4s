package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.TransportCause

/** A request that never produced a response.
  *
  * This is deliberately not a [[com.worxbend.codeberg4s.CodebergError]]: the transport adapter does not know the
  * operation name or how long the call took in the pipeline's terms, so it reports only the cause and the request
  * pipeline builds the [[com.worxbend.codeberg4s.CodebergError.Transport]] around it.
  *
  * @param cause
  *   the classified reason, or [[com.worxbend.codeberg4s.TransportCause.Unknown]] when the adapter could not classify
  *   the exception it caught
  */
final case class TransportFailure(cause: TransportCause)
