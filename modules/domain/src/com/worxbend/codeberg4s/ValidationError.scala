package com.worxbend.codeberg4s

/** A single pre-flight validation failure produced by a smart constructor.
  *
  * Validation happens before any request is built, so a `ValidationError` never carries a [[CallContext]]: there is no
  * call yet. It is lifted into [[CodebergError.Validation]] when it has to travel on the same channel as remote
  * failures.
  *
  * @param field
  *   the lower-camel-case name of the rejected concept, stable enough to branch on — `"owner"`, `"repoName"`,
  *   `"apiToken"`, `"baseUri"`, `"pageSize"`
  * @param message
  *   a short, human-readable reason, lowercase and without a trailing period. It never contains credential material.
  */
final case class ValidationError(field: String, message: String)
