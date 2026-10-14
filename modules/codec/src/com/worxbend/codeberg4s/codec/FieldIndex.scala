package com.worxbend.codeberg4s.codec

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap

/** Shared bounded name index for parser duplicate detection and DTO lookup.
  *
  * Ordinary objects use one open-addressed array. No insertion or lookup probes more than MaxProbes slots. A cluster
  * exceeding that budget switches the whole object to comparison-ordered first positions: an immutable red-black tree
  * builds them in O(n log n), then lookup is binary search, independent of hashes. Arrays are confined to construction
  * and never exposed or mutated after publication.
  */
private[codec] final class FieldIndex private (
    private val slots: Array[Int],
    private val ordered: Array[Int],
    val firstDuplicate: Int,
    val buildProbes: Long,
    val buildComparisons: Long,
):
  def usesFallback: Boolean = ordered.nonEmpty

  def lookup(entries: Vector[(String, JsonValue)], name: String): Int =
    locate(entries, name, FieldIndex.IgnoreVisit)

  /** Counts real slot visits / string comparisons for deterministic tests, with no process-global counters. */
  def measuredLookup(entries: Vector[(String, JsonValue)], name: String): FieldIndex.Lookup =
    val visits   = Array(0)
    val position = locate(entries, name, () => visits(0) += 1)
    FieldIndex.Lookup(position, if usesFallback then 0 else visits(0), if usesFallback then visits(0) else 0)

  private def locate(entries: Vector[(String, JsonValue)], name: String, visit: () => Unit): Int =
    if usesFallback then binary(entries, name, 0, ordered.length - 1, visit)
    else probe(entries, name, FieldIndex.slotFor(name, slots.length), 0, visit)

  @tailrec private def probe(
      entries: Vector[(String, JsonValue)],
      name: String,
      slot: Int,
      visited: Int,
      visit: () => Unit,
  ): Int =
    if visited >= FieldIndex.MaxProbes then -1
    else
      visit()
      val taken = slots(slot) - 1
      if taken < 0 then -1
      else if entries(taken)._1.contentEquals(name) then taken
      else probe(entries, name, (slot + 1) & (slots.length - 1), visited + 1, visit)

  @tailrec private def binary(
      entries: Vector[(String, JsonValue)],
      name: String,
      low: Int,
      high: Int,
      visit: () => Unit,
  ): Int =
    if low > high then -1
    else
      val middle     = low + (high - low) / 2
      visit()
      val comparison = entries(ordered(middle))._1.compareTo(name)
      if comparison < 0 then binary(entries, name, middle + 1, high, visit)
      else if comparison > 0 then binary(entries, name, low, middle - 1, visit)
      else ordered(middle)

private[codec] object FieldIndex:
  val MaxProbes: Int                  = 32
  private val IgnoreVisit: () => Unit = () => ()

  final case class Lookup(position: Int, linearProbes: Int, orderedComparisons: Int)

  def build(entries: Vector[(String, JsonValue)]): FieldIndex =
    val slots  = new Array[Int](JsonValue.indexSize(math.max(1, entries.size)))
    val probes = Array(0L)

    @tailrec def place(position: Int, slot: Int, visited: Int): Int =
      if visited >= MaxProbes then -1
      else
        probes(0) += 1
        val taken = slots(slot) - 1
        if taken < 0 then
          slots(slot) = position + 1
          position
        else if entries(taken)._1.contentEquals(entries(position)._1) then taken
        else place(position, (slot + 1) & (slots.length - 1), visited + 1)

    @tailrec def buildFrom(position: Int, duplicate: Int): FieldIndex =
      if position >= entries.size then new FieldIndex(slots, Array.emptyIntArray, duplicate, probes(0), 0L)
      else
        val first = place(position, slotFor(entries(position)._1, slots.length), 0)
        if first < 0 then comparisonIndex(entries, probes(0))
        else
          val repeated = if duplicate < 0 && first < position then position else duplicate
          buildFrom(position + 1, repeated)

    buildFrom(0, -1)

  private def comparisonIndex(entries: Vector[(String, JsonValue)], probes: Long): FieldIndex =
    val comparisons                = Array(0L)
    val ordering: Ordering[String] = new Ordering[String]:
      def compare(left: String, right: String): Int =
        comparisons(0) += 1
        left.compareTo(right)

    @tailrec def collect(position: Int, first: TreeMap[String, Int], duplicate: Int): FieldIndex =
      if position >= entries.size then
        new FieldIndex(Array.emptyIntArray, first.valuesIterator.toArray, duplicate, probes, comparisons(0))
      else
        val name = entries(position)._1
        first.get(name) match
          case Some(_) => collect(position + 1, first, if duplicate < 0 then position else duplicate)
          case None    => collect(position + 1, first.updated(name, position), duplicate)

    collect(0, TreeMap.empty[String, Int](using ordering), -1)

  private def slotFor(name: String, length: Int): Int =
    val hash = name.hashCode
    (hash ^ (hash >>> 16)) & (length - 1)
