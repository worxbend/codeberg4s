package com.worxbend.codeberg4s.codec

import munit.FunSuite

/** Exercises the actual shared index. Structural work budgets, never elapsed-time assertions. */
final class FieldIndexBudgetSuite extends FunSuite:
  private val family: Vector[String] = (0 until 14).foldLeft(Vector(""))((keys, _) =>
    keys.flatMap(key => Vector(s"${key}Aa", s"${key}BB"))
  )

  private def entries(width: Int): Vector[(String, JsonValue)] =
    family.take(width).zipWithIndex.map((key, index) => key -> JsonValue.Int64(index.toLong))

  test("an exactly budget-sized collision cluster stays on the hashed path"):
    val fields = entries(FieldIndex.MaxProbes)
    val index  = FieldIndex.build(fields)
    assert(!index.usesFallback)
    assertEquals(index.buildComparisons, 0L)
    fields.indices.foreach: position =>
      val read = index.measuredLookup(fields, fields(position)._1)
      assertEquals(read.position, position)
      assert(read.linearProbes <= FieldIndex.MaxProbes)

  test("one key beyond the per-insertion budget deterministically triggers fallback"):
    val fields = entries(FieldIndex.MaxProbes + 1)
    val index  = FieldIndex.build(fields)
    assert(index.usesFallback)
    assert(index.buildProbes <= FieldIndex.MaxProbes.toLong * fields.size)
    assert(index.buildComparisons > 0L)

  Vector(64, 257, 1024, 4096, 8192).foreach: width =>
    test(s"$width colliding fields build and lookup obey structural work budgets"):
      val fields           = entries(width)
      val index            = FieldIndex.build(fields)
      val logarithmicBound = Integer.SIZE - Integer.numberOfLeadingZeros(width)
      assert(index.usesFallback)
      assertEquals(index.firstDuplicate, -1)
      assert(index.buildProbes <= FieldIndex.MaxProbes.toLong * width)
      // Red-black tree get + insert: each descends at most twice the binary-tree height bound.
      assert(index.buildComparisons <= 8L * width * logarithmicBound)
      fields.indices.foreach: position =>
        val read = index.measuredLookup(fields, fields(position)._1)
        assertEquals(read.position, position)
        assertEquals(read.linearProbes, 0)
        assert(read.orderedComparisons <= logarithmicBound)
      val absent           = index.measuredLookup(fields, family.last)
      assertEquals(absent.position, -1)
      assertEquals(absent.linearProbes, 0)
      assert(absent.orderedComparisons <= logarithmicBound)

  Vector(16, 64, 257).foreach: width =>
    test(s"duplicates at width $width retain their original first position on either index path"):
      val fields   = entries(width)
      val repeated = fields :+ (family.head -> JsonValue.Null) :+ (family.last -> JsonValue.Null) :+
        (family.last -> JsonValue.Str("later"))
      val index    = FieldIndex.build(repeated)
      assertEquals(index.firstDuplicate, width)
      assertEquals(index.lookup(repeated, family.head), 0)
      assertEquals(index.lookup(repeated, family.last), width + 1)
      assertEquals(JsonFields(repeated).value(family.last), None)
      assert(Json.parse(Json.render(JsonValue.Obj(repeated))).isLeft)

  test("an absent collision lookup is capped even before a fallback is needed"):
    val fields = entries(FieldIndex.MaxProbes)
    val read   = FieldIndex.build(fields).measuredLookup(fields, family.last)
    assertEquals(read.position, -1)
    assertEquals(read.linearProbes, FieldIndex.MaxProbes)
