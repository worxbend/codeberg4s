package com.worxbend.codeberg4s

/** A single pre-flight validation failure produced by a smart constructor.
  *
  * This is another name for [[CodebergError.Validation]], the case of the error family that carries a rejected
  * argument. The name exists because it reads better at a smart constructor's result type: `Either[ValidationError,
  * Owner]` says what `Owner.from` can go wrong with more directly than the qualified enum case does.
  *
  * Being a case of [[CodebergError]] rather than a type of its own is what lets one `for`-comprehension mix a smart
  * constructor with a client call. `Either`'s `flatMap` widens its left type to the nearest common supertype, so a
  * `for` that starts with `Owner.from(raw)` and continues with a call returning `Either[CodebergError, Repository]`
  * type-checks on its own and yields an `Either[CodebergError, Repository]`, with no mapping step in between.
  *
  * Validation happens before any request is built, so it never carries a [[CallContext]]: there is no call yet.
  */
type ValidationError = CodebergError.Validation

/** Builds and takes apart a [[ValidationError]] without naming the enum it is a case of.
  *
  * Both members state [[ValidationError]] as their type rather than [[CodebergError]]. That is deliberate: an enum
  * case's own constructor widens its result to the enum when nothing asks for the precise type, which would make
  * `Left(ValidationError("owner", "must not be blank"))` a `Left[CodebergError, ?]` and stop it being the failure a
  * smart constructor promises.
  */
object ValidationError:

  /** @param field
    *   the lower-camel-case name of the rejected concept, stable enough to branch on — `"owner"`, `"repoName"`,
    *   `"apiToken"`, `"baseUri"`, `"pageSize"`
    * @param message
    *   a short, human-readable reason, lowercase and without a trailing period. It never contains credential material.
    */
  def apply(field: String, message: String): ValidationError =
    CodebergError.Validation(field, message)

  /** Irrefutable extractor, so `case ValidationError(field, message)` reads a failure apart. */
  def unapply(error: ValidationError): (String, String) =
    (error.field, error.message)
