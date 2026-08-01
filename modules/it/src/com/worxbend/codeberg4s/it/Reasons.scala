package com.worxbend.codeberg4s.it

import com.worxbend.codeberg4s.ValidationError

import scala.util.Try

/** Turns the two kinds of setup failure this module meets into one plain reason string.
  *
  * The integration lane talks to a container and to a live instance, so its setup code fails in ways the library itself
  * never does: a Docker daemon that is not there, an image that cannot be pulled, an instance that answers HTML where
  * JSON was expected. None of that is a [[com.worxbend.codeberg4s.CodebergError]] — there is no call context to attach
  * — so bootstrap code reports `Either[String, A]` and a suite renders the `Left` through munit's `fail`.
  *
  * '''Error contract.''' Nothing here throws and nothing here logs. [[attempting]] converts a thrown failure into a
  * `Left` carrying the exception's message, never its stack trace; [[invalid]] renders a
  * [[com.worxbend.codeberg4s.ValidationError]], whose `message` is documented never to contain credential material.
  * Callers must therefore never pass a command line or a request body that carries a password or a token into `what`,
  * because that string reaches the reason and from there a test report.
  */
object Reasons:

  /** Runs `action`, turning any non-fatal failure into a reason prefixed by `what`.
    *
    * @param what
    *   what was being attempted, phrased so that `"$what failed: …"` reads as a sentence, and carrying no credentials
    * @param action
    *   the effect to run; evaluated exactly once
    * @return
    *   the value, or the reason the attempt failed
    */
  def attempting[A](what: String)(action: => A): Either[String, A] =
    Try(action).toEither.left.map(failure => s"$what failed: ${failure.getMessage}")

  /** Renders a smart constructor's rejection as a reason string. */
  def invalid(error: ValidationError): String =
    s"${error.field} ${error.message}"
