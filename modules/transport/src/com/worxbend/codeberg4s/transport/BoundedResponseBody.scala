package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.core.TransportFailure

import scala.annotation.tailrec
import scala.util.control.NonFatal

import java.io.{ByteArrayOutputStream, InputStream}

/** The adapter's incremental decoded-byte allocation boundary. */
private[transport] object BoundedResponseBody:

  /** Reads decoded bytes incrementally, including one probe byte at the bound. The remaining Long is narrowed only
    * after capping it to the fixed buffer. Never allocate from a peer Content-Length or from the configured limit.
    * Closure is on reader exit (also with BackendStub), not at logical timeout; native sttp additionally owns
    * acquisition/decompression-failure cleanup. Nonfatal close failures cannot replace an established refusal or read
    * throwable (secondary exceptions are suppressed onto a primary throwable, with self-suppression avoided). Close
    * failure after successful bytes is a failure. Fatal/interrupt cleanup causes, including boxed causes, always
    * escape; an over-budget cause chain is preserved rather than discarded.
    */
  def read(stream: InputStream, limit: Long): Either[TransportFailure, Array[Byte]] =
    // Capture even raw fatal read failures solely to attempt cleanup and rethrow;
    // never turn them into an ordinary transport refusal. Cleanup is outside this
    // try, so a failed close after success is not mistaken for a read failure.
    val outcome: Either[Throwable, Either[TransportFailure, Array[Byte]]] =
      try Right(readOpen(stream, limit))
      catch case primary: Throwable => Left(primary)
    outcome match
      case Left(primary) =>
        try stream.close()
        catch
          case NonFatal(secondary) if !SttpHttpPort.mustPropagateCleanupFailure(secondary) =>
            if !secondary.eq(primary) then primary.addSuppressed(secondary)
        // Foreign throwable identity, including fatal identity, is the contract.
        throw primary // scalafix:ok DisableSyntax
      case Right(result) =>
        result match
          case Left(_)  =>
            // A structured refusal has no throwable to suppress onto. Discard
            // only nonfatal cleanup failures; raw fatal cleanup always escapes.
            try stream.close()
            catch case NonFatal(secondary) if !SttpHttpPort.mustPropagateCleanupFailure(secondary) => ()
          case Right(_) => stream.close()
        result

  private def readOpen(stream: InputStream, limit: Long): Either[TransportFailure, Array[Byte]] =
    val output                                                       = ByteArrayOutputStream()
    val buffer                                                       = new Array[Byte](8192)
    // An Array[Byte] cannot represent more than Int.MaxValue bytes even if the
    // caller's Long limit is larger. Small bodies under such limits still work.
    val effectiveLimit                                               = math.min(limit, Int.MaxValue.toLong)
    @tailrec
    def read(remaining: Long): Either[TransportFailure, Array[Byte]] =
      if remaining <= 0L then
        if stream.read() < 0 then Right(output.toByteArray)
        else
          Left(TransportFailure(TransportCause.ResponseTooLarge(s"decoded stream length limit of $limit bytes exceeded")))
      else
        val count = stream.read(buffer, 0, math.min(remaining, buffer.length.toLong).toInt)
        if count < 0 then Right(output.toByteArray)
        else if count > 0 then
          output.write(buffer, 0, count)
          read(remaining - count.toLong)
        else
          // Defensive progress for foreign streams returning zero on a read.
          val byte = stream.read()
          if byte < 0 then Right(output.toByteArray)
          else
            output.write(byte)
            read(remaining - 1L)
    if limit < 0L then Left(TransportFailure(TransportCause.Unknown("response byte limit must not be negative")))
    else read(effectiveLimit)
