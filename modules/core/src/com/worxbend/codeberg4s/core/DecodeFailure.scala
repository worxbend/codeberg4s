package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.JsonPath

/** Why a response body did not match the model it was decoded into.
  *
  * Carries no body snippet: the request pipeline owns the body and adds a bounded excerpt when it lifts this into
  * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]], which keeps the bound in one place.
  *
  * @param path
  *   where in the document decoding stopped, [[com.worxbend.codeberg4s.JsonPath.Root]] when the failure is about the
  *   document as a whole
  * @param message
  *   a short human-readable reason, from the codec; never the whole payload
  */
final case class DecodeFailure(path: JsonPath, message: String)
