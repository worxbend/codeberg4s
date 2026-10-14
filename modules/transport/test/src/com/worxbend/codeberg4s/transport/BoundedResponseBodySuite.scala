package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.core.TransportFailure
import com.worxbend.codeberg4s.syntax.discard

import munit.FunSuite

import scala.util.{Failure, Try}

import java.io.{ByteArrayInputStream, FilterInputStream, IOException, InputStream}
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/** Small deterministic controls for the decoded response reader. Native decompression/acquisition cleanup stays in
  * TransportStreamRegressionSuite.
  */
final class BoundedResponseBodySuite extends FunSuite:
  private val bytes = Array[Byte](0, 1, -1, 2)

  for limit <- List(3L, 4L, 5L) do
    test(s"decoded read under limit $limit preserves bytes or rejects before consuming more than limit plus one"):
      val stream = Probe(ByteArrayInputStream(bytes))
      val result = BoundedResponseBody.read(stream, limit)
      if limit < bytes.length.toLong then assertTooLarge(result)
      else assertEquals(result.map(_.toList), Right(bytes.toList))
      assert(stream.closed.get())
      assert(stream.consumed.get().toLong <= limit + 1L)

  test("an empty response at a zero bound succeeds and closes"):
    val stream = Probe(ByteArrayInputStream(Array.emptyByteArray))
    assertEquals(BoundedResponseBody.read(stream, 0L).map(_.toList), Right(Nil))
    assert(stream.closed.get())
    assertEquals(stream.consumed.get(), 0)

  test("a nonempty response at a zero bound reads only its rejecting probe and closes"):
    val stream = Probe(ByteArrayInputStream(bytes))
    assertTooLarge(BoundedResponseBody.read(stream, 0L))
    assert(stream.closed.get())
    assertEquals(stream.consumed.get(), 1)

  test("Long.MaxValue is not narrowed into a negative read or allocation size"):
    val stream = Probe(ByteArrayInputStream(bytes))
    assertEquals(BoundedResponseBody.read(stream, Long.MaxValue).map(_.toList), Right(bytes.toList))
    assert(stream.closed.get())

  test("a foreign stream returning zero from bulk reads still makes bounded progress"):
    val zeroBulk = new ByteArrayInputStream(bytes):
      override def read(buffer: Array[Byte], offset: Int, length: Int): Int = 0
    val stream   = Probe(zeroBulk)
    assertEquals(BoundedResponseBody.read(stream, 4L).map(_.toList), Right(bytes.toList))
    assertEquals(stream.consumed.get(), 4)
    assert(stream.closed.get())

  test("a negative internal limit is refused without reading and still closes"):
    val stream = Probe(ByteArrayInputStream(bytes))
    val result = BoundedResponseBody.read(stream, -1L)
    assertEquals(result, Left(TransportFailure(TransportCause.Unknown("response byte limit must not be negative"))))
    assertEquals(stream.consumed.get(), 0)
    assert(stream.closed.get())

  test("a foreign read failure closes the stream and retains its throwable"):
    val error  = IOException("synthetic reader failure")
    val stream = Probe(new InputStream:
      override def read(): Int                                              = throw error
      override def read(buffer: Array[Byte], offset: Int, length: Int): Int = throw error)
    assertEquals(Try(BoundedResponseBody.read(stream, 4L)), Failure(error))
    assert(stream.closed.get())

  test("a decoded limit refusal remains primary when closing throws I/O"):
    val stream = ClosingFailureStream(ByteArrayInputStream(bytes), IOException("secondary close failure"))
    val result = BoundedResponseBody.read(stream, 3L)
    assertTooLarge(result)
    assertEquals(stream.closes.get(), 1)

  test("a negative-limit refusal remains primary when closing throws I/O"):
    val stream = ClosingFailureStream(ByteArrayInputStream(bytes), IOException("secondary close failure"))
    val result = BoundedResponseBody.read(stream, -1L)
    assertEquals(result, Left(TransportFailure(TransportCause.Unknown("response byte limit must not be negative"))))
    assertEquals(stream.reads.get(), 0)
    assertEquals(stream.closes.get(), 1)

  test("a primary read IOException retains identity despite a secondary close IOException"):
    val primary = IOException("primary read failure")
    val closing = IOException("secondary close failure")
    val stream  = ClosingFailureStream(failingRead(primary), closing)
    val result  = Try(BoundedResponseBody.read(stream, 4L))
    result match
      case Failure(error) => assert(error eq primary, "secondary close replaced the primary read throwable")
      case other          => fail(s"read failure was accepted: $other")
    assertEquals(stream.closes.get(), 1)
    assert(primary.getSuppressed.exists(_ eq closing), "secondary close failure was not retained")

  test("a synthetic fatal primary retains identity despite a nonfatal close failure"):
    // No VM error and no fatal Future worker: catch only this synthetic linkage
    // sentinel at the direct helper boundary.
    val primary = LinkageError("synthetic primary read failure")
    val stream  = ClosingFailureStream(failingRead(primary), IOException("secondary close failure"))
    val caught  =
      try
        BoundedResponseBody.read(stream, 4L).discard
        None
      catch
        case error: LinkageError => Some(error)
    assert(caught.exists(_ eq primary), "synthetic fatal primary was lost")
    assertEquals(stream.closes.get(), 1)

  test("a close IOException after otherwise successful bytes is a legitimate failure"):
    val closing = IOException("successful read could not close")
    val stream  = ClosingFailureStream(ByteArrayInputStream(bytes), closing)
    assertEquals(Try(BoundedResponseBody.read(stream, 4L)), Failure(closing))
    assertEquals(stream.closes.get(), 1)

  test("the same throwable from read and close cannot self-suppress or replace itself"):
    val primary = IOException("same foreign read and close failure")
    val stream  = ClosingFailureStream(failingRead(primary), primary)
    Try(BoundedResponseBody.read(stream, 4L)) match
      case Failure(error) => assert(error eq primary)
      case other          => fail(s"read failure was accepted: $other")
    assertEquals(primary.getSuppressed.toList, Nil)
    assertEquals(stream.closes.get(), 1)

  for outcome <- List("success", "refusal", "read failure") do
    test(s"raw fatal close escapes rather than becoming an ordinary $outcome outcome"):
      val fatal    = LinkageError("synthetic fatal close failure")
      val primary  = IOException("primary read failure")
      val delegate = if outcome == "read failure" then failingRead(primary) else ByteArrayInputStream(bytes)
      val stream   = ClosingFailureStream(delegate, fatal)
      val limit    = if outcome == "refusal" then 3L else 4L
      val caught   =
        try
          BoundedResponseBody.read(stream, limit).discard
          None
        catch
          case error: LinkageError => Some(error)
      assert(caught.exists(_ eq fatal), "fatal close was discarded or converted to an ordinary result")
      assertEquals(stream.closes.get(), 1)

  for outcome <- List("success", "refusal", "read failure") do
    test(s"a boxed fatal close retains its failed-effect identity after $outcome"):
      val fatal    = LinkageError("synthetic boxed close failure")
      val boxed    = ExecutionException("boxed fatal close", fatal)
      val primary  = IOException("ordinary primary read failure")
      val delegate = if outcome == "read failure" then failingRead(primary) else ByteArrayInputStream(bytes)
      val stream   = ClosingFailureStream(delegate, boxed)
      val limit    = if outcome == "refusal" then 3L else 4L
      Try(BoundedResponseBody.read(stream, limit)) match
        case Failure(delivered) => assert(delivered.eq(boxed), "boxed fatal close was discarded or replaced")
        case other              => fail(s"boxed fatal close became an ordinary result: $other")
      assertEquals(stream.closes.get(), 1)

  private def failingRead(error: Throwable): InputStream = new InputStream:
    override def read(): Int                                              = throw error
    override def read(buffer: Array[Byte], offset: Int, length: Int): Int = throw error

  private final class ClosingFailureStream(delegate: InputStream, closing: Throwable)
      extends FilterInputStream(delegate):
    val reads: AtomicInteger  = AtomicInteger()
    val closes: AtomicInteger = AtomicInteger()

    override def read(): Int =
      reads.incrementAndGet().discard
      in.read()

    override def read(buffer: Array[Byte], offset: Int, length: Int): Int =
      reads.incrementAndGet().discard
      in.read(buffer, offset, length)

    override def close(): Unit =
      closes.incrementAndGet().discard
      super.close()
      throw closing

  private def assertTooLarge(result: Either[TransportFailure, Array[Byte]]): Unit =
    result match
      case Left(TransportFailure(_: TransportCause.ResponseTooLarge)) => ()
      case other => fail(s"expected decoded byte refusal, got $other")

  private final class Probe(delegate: InputStream) extends FilterInputStream(delegate):
    val consumed: AtomicInteger = AtomicInteger()
    val closed: AtomicBoolean   = AtomicBoolean(false)

    override def read(): Int =
      val value = in.read()
      if value >= 0 then consumed.incrementAndGet().discard
      value

    override def read(buffer: Array[Byte], offset: Int, length: Int): Int =
      val count = in.read(buffer, offset, length)
      if count > 0 then consumed.addAndGet(count).discard
      count

    override def close(): Unit =
      try super.close()
      finally closed.set(true)
