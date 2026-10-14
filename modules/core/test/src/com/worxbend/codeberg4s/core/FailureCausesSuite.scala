package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.syntax.discard

import munit.FunSuite

import java.io.IOException
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

final class FailureCausesSuite extends FunSuite:

  private final class Node extends IOException("synthetic graph node"):
    private val next                     = AtomicReference[Throwable]()
    val reads                            = AtomicInteger()
    def pointsTo(error: Throwable): Unit = next.set(error)

    override def getCause: Throwable =
      reads.incrementAndGet().discard
      next.get()

  test("shared cause and suppressed nodes are snapshotted once without changing primary ordering"):
    val root  = Node()
    val child = Node()
    root.pointsTo(child)
    root.addSuppressed(child)
    val graph = FailureCauses.inspect(root).getOrElse(fail("small graph was refused"))
    assertEquals(graph.primary, List(root, child))
    assertEquals(graph.all, List(root, child))
    assertEquals(root.reads.get(), 1)
    assertEquals(child.reads.get(), 1)

  test("suppressed cycles terminate by identity and stay separate from the primary chain"):
    val root    = Node()
    val cleanup = Node()
    root.addSuppressed(cleanup)
    cleanup.addSuppressed(root)
    val graph   = FailureCauses.inspect(root).getOrElse(fail("small cycle was refused"))
    assertEquals(graph.primary, List(root))
    assertEquals(graph.all, List(root, cleanup))
    assertEquals(root.reads.get(), 1)
    assertEquals(cleanup.reads.get(), 1)

  test("a deep graph is conservatively refused within the shared node budget"):
    val nodes = Vector.fill(70)(Node())
    nodes.zip(nodes.tail).foreach((left, right) => left.pointsTo(right))
    assertEquals(FailureCauses.inspect(nodes.head), None)
    assert(nodes.map(_.reads.get()).sum <= 64)

  test("a wide suppressed graph is refused rather than silently truncated"):
    val root = Node()
    (0 until 65).foreach(_ => root.addSuppressed(IOException("synthetic cleanup")))
    assertEquals(FailureCauses.inspect(root), None)
    assertEquals(root.reads.get(), 1)

  test("a foreign throwing cause accessor retains uncertainty rather than replacing the original failure"):
    val broken = new IOException("synthetic accessor"):
      override def getCause: Throwable = throw IllegalStateException("synthetic accessor defect")
    assertEquals(FailureCauses.inspect(broken), None)
