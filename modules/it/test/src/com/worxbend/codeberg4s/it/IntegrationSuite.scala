package com.worxbend.codeberg4s.it

import munit.FunSuite

/** The tag every suite in `modules/it` carries.
  *
  * `modules/it` is the environmentally unsuitable boundary described in `CLAUDE.md` and `PLAN.md` §6.5: the one module
  * allowed to start a container or open a socket to the public internet. Two independent things keep it out of the
  * default gate, and both are deliberate:
  *
  *   1. `verify.sh` names the unit-test modules one by one and `modules.it.test` is not among them, so the default run
  *      never reaches this code at all;
  *   1. every test here is tagged [[Integration.Tag]], so a run that '''does''' reach it — `mill modules.__.test`, or a
  *      future gate that widens its module list — can still exclude it with `--exclude-tags=Integration`.
  *
  * The belt and the braces exist because the module list is easy to widen by accident and a tag is not.
  */
object Integration:

  /** The munit tag. Filter on the literal string `Integration`. */
  val Tag: munit.Tag = new munit.Tag("Integration")

/** The base suite for this module: whatever a subclass declares, it is tagged [[Integration.Tag]].
  *
  * Tagging happens here rather than at each `test(…)` call so that a new suite cannot be added to the module without
  * the tag, which is the failure mode a per-test `.tag(…)` invites.
  */
trait IntegrationSuite extends FunSuite:

  /** Every test this suite declares, each carrying [[Integration.Tag]] in addition to its own tags. */
  override def munitTests(): Seq[Test] =
    super.munitTests().map(one => one.tag(Integration.Tag))
