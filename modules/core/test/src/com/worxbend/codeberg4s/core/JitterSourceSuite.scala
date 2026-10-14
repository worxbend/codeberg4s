package com.worxbend.codeberg4s.core

import munit.FunSuite

/** Direct port controls: a millisecond-grid engine must not hide inclusive-bound overflow in the source itself. */
final class JitterSourceSuite extends FunSuite:

  test("the production source accepts the inclusive Long.MaxValue bound directly"):
    (0 until 64).foreach: _ =>
      val draw = JitterSource.Random.upTo(Long.MaxValue)
      assert(draw >= 0L && draw <= Long.MaxValue)

  test("the production source stays within ordinary and near-maximum inclusive bounds"):
    Vector(1L, 251L, Long.MaxValue / 2L, Long.MaxValue - 1L).foreach: bound =>
      (0 until 64).foreach: _ =>
        val draw = JitterSource.Random.upTo(bound)
        assert(draw >= 0L && draw <= bound)

  test("both built-in sources return zero for non-positive bounds"):
    Vector(JitterSource.Random, JitterSource.Deterministic).foreach: source =>
      Vector(Long.MinValue, -1L, 0L).foreach: bound =>
        assertEquals(source.upTo(bound), 0L)

  test("the deterministic source includes every positive upper endpoint including Long.MaxValue"):
    Vector(1L, 251L, Long.MaxValue / 2L, Long.MaxValue - 1L, Long.MaxValue).foreach: bound =>
      assertEquals(JitterSource.Deterministic.upTo(bound), bound)
