package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.syntax.discard

import scala.collection.mutable.ListBuffer

/** An [[HttpPort]] that replays a scripted sequence of outcomes instead of reaching the network.
  *
  * The *n*-th send returns the *n*-th scripted outcome, and every send past the end of the script repeats the last one.
  * That is what makes a retry observable: a script of `Vector(serverError, success)` proves the pipeline came back a
  * second time, and a one-element script proves it did not.
  *
  * @param outcomes
  *   the outcomes to replay, in order; must not be empty
  */
final class FakeHttpPort(outcomes: Vector[Either[TransportFailure, CodebergResponse]]) extends HttpPort[Exec.Result]:

  private val recorded = ListBuffer.empty[(CodebergRequest, String)]

  override def send(
      request: CodebergRequest,
      redactedUri: String,
  ): Exec.Result[Either[TransportFailure, CodebergResponse]] =
    recorded.append((request, redactedUri)).discard
    Right(outcomes(math.min(recorded.size - 1, outcomes.size - 1)))

  /** Every request this port was asked to send, in order. */
  def requests: Vector[CodebergRequest] = recorded.toVector.map((request, _) => request)

  /** Every redacted URI the pipeline handed over — the security-relevant half of a send. */
  def uris: Vector[String] = recorded.toVector.map((_, uri) => uri)

  /** How many times the port was called. */
  def sends: Int = recorded.size

object FakeHttpPort:

  /** A port that answers every send with the same response. */
  def always(response: CodebergResponse): FakeHttpPort =
    FakeHttpPort(Vector(Right(response)))

  /** A port that never produces a response. */
  def broken(failure: TransportFailure): FakeHttpPort =
    FakeHttpPort(Vector(Left(failure)))
