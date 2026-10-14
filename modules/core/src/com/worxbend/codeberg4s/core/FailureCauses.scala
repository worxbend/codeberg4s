package com.worxbend.codeberg4s.core

import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Bounded snapshots of foreign failure graphs, shared by transport adaptation and telemetry guards. Primary cause
  * ordering remains separate from suppressed cleanup branches: ordinary suppressed diagnoses must not replace the
  * primary transport category. Policy for fatal errors and interrupts stays with each boundary.
  */
private[codeberg4s] object FailureCauses:
  private val MaxNodes: Int = 64

  final case class Graph(primary: List[Throwable], all: List[Throwable])
  private final case class Node(error: Throwable, cause: Option[Throwable])

  /** Reads each distinct node's cause/suppressed edges once. Cycles terminate by identity; an uninspectable or
    * over-budget graph yields None so callers preserve the original failed outcome rather than hide an unseen fatal.
    */
  def inspect(error: Throwable): Option[Graph] =
    @tailrec def capture(todo: List[Throwable], seen: List[Node]): Option[List[Node]] = todo match
      case Nil                                           => Some(seen.reverse)
      case head :: rest if seen.exists(_.error.eq(head)) => capture(rest, seen)
      case _ if seen.size >= MaxNodes                    => None
      case head :: rest                                  =>
        val cause      = Option(head.getCause)
        val suppressed = head.getSuppressed
        if suppressed.length > MaxNodes then None
        else capture(cause.toList ::: suppressed.toList ::: rest, Node(head, cause) :: seen)

    @tailrec def primary(next: Option[Throwable], snapshots: List[Node], seen: List[Throwable]): List[Throwable] =
      next match
        case None                                  => seen.reverse
        case Some(head) if seen.exists(_.eq(head)) => seen.reverse
        case Some(head) => primary(snapshots.find(_.error.eq(head)).flatMap(_.cause), snapshots, head :: seen)

    try capture(List(error), Nil).map(nodes => Graph(primary(Some(error), nodes, Nil), nodes.map(_.error)))
    catch case NonFatal(_) => None
