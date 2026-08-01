package com.worxbend.codeberg4s.syntax

/** Explicitly throws a value away.
  *
  * The build runs with `-Wvalue-discard` and `-Wnonunit-statement`, so a non-`Unit` expression in statement position is
  * a compile error. That is usually the compiler catching a real mistake; when it is not — a builder that returns
  * `this`, a channel operation whose result genuinely does not matter — `discard` says so at the call site instead of
  * silencing the warning globally.
  *
  * {{{
  * import com.worxbend.codeberg4s.syntax.discard
  *
  * buffer.append(item).discard
  * }}}
  *
  * Prefer restructuring the code over reaching for this. Each use should be obviously deliberate to a reviewer.
  */
extension [A](value: A)

  /** Evaluates the receiver and yields `Unit`. */
  def discard: Unit = Function.const(())(value)
