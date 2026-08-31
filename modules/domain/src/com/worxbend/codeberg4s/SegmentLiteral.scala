package com.worxbend.codeberg4s

import scala.compiletime.codeOf
import scala.compiletime.constValue
import scala.compiletime.constValueOpt
import scala.compiletime.error
import scala.compiletime.ops.string.Matches

/** The compile-time half of [[PathSegment]] — the same rules, checked while the code compiles.
  *
  * ==Why this exists==
  *
  * Almost every identifier in this library is written down as a string literal by the programmer, not computed at run
  * time: `Owner("forgejo")`, `BranchName("main")`, `RepoName("codeberg4s")`. A literal is either valid or it is not,
  * and which one it is can be decided before the program ever runs. Yet `Owner.from` returns `Either[ValidationError,
  * Owner]`, so every one of those literals used to drag a `for` comprehension or an `orFail` helper behind it purely to
  * discharge a failure that cannot happen.
  *
  * The `apply` on each identifier's companion — `Owner("forgejo")` — is that same check moved to compile time. It takes
  * the value, returns the identifier with no `Either` around it, and if the literal is invalid the '''compiler''' says
  * so, at the call site, pointing at the offending literal. `from` remains the way in for a value that is only known at
  * run time: a command-line argument, a config file, a field of a decoded response.
  *
  * ==How it works, and what it costs==
  *
  * There is no macro here. Each check is a single `inline if` over [[scala.compiletime.ops.string.Matches]], which asks
  * the compiler whether a literal '''type''' matches a regular expression. That happens during typing, so the whole
  * call folds away to the literal string; nothing of this object survives into the bytecode of the call site.
  *
  * The price is that the rule is written twice — once as the readable `if`/`else` chain in [[PathSegment]], and once as
  * a regular expression here — which is exactly the duplication [[PathSegment]]'s own Scaladoc warns about. It is
  * accepted for one reason only: `PathSegment.from` cannot be called at compile time without a macro, and a macro would
  * need its own compilation unit. `SegmentLiteralSuite` pins the two spellings together by checking the same values
  * through both, so a change to one that is not mirrored in the other fails the build rather than drifting quietly.
  *
  * ==Why it is public==
  *
  * It is not part of the API anyone should call. It has to be public because an `inline def` is expanded at the call
  * site, in the caller's own code, so everything it mentions has to be reachable from there. Call the identifier
  * companions — `Owner("forgejo")` — never this.
  */
object SegmentLiteral:

  /** The regular expression form of [[PathSegment.from]]'s rules.
    *
    * Reading it clause by clause: `(?!\.\.?$)` refuses the traversal segments `.` and `..`; the alternation that
    * follows demands at least one character, forbids `/` and any control character throughout, and forbids whitespace
    * at either end. That last clause is where the literal check is deliberately '''stricter''' than
    * [[PathSegment.from]], which trims: `Owner(" forgejo ")` is refused rather than silently accepted as `"forgejo"`,
    * because a literal with stray whitespace in it is a typo the programmer can simply fix, and quietly changing what
    * someone wrote is worse than telling them.
    */
  type Plain = "(?!\\.\\.?$)(?:[^/\\s\\p{Cntrl}]|[^/\\s\\p{Cntrl}][^/\\p{Cntrl}]*[^/\\s\\p{Cntrl}])"

  /** The regular expression form of [[PathSegment.segmented]]'s rules.
    *
    * Same shape as [[Plain]], with `/` allowed as a separator and four leading lookaheads guarding it. In order, they
    * refuse: a `.` or `..` anywhere among the segments; a leading slash; a doubled slash, which is the empty segment in
    * the middle of `a//b`; and a trailing slash. (They are written as lookaheads rather than spelled into the body
    * because a regular expression that describes "no segment is `..`" positionally is unreadable.)
    */
  type Segmented =
    "(?!.*(?:^|/)\\.\\.?(?:/|$))(?!/)(?!.*//)(?!.*/$)(?:[^\\s\\p{Cntrl}]|[^\\s\\p{Cntrl}][^\\p{Cntrl}]*[^\\s\\p{Cntrl}])"

  /** Accepts `value` if it can stand alone as one path segment, and fails the compilation if it cannot.
    *
    * @param field
    *   the field name to name in the compile error, matching the one [[PathSegment.from]] reports at run time
    */
  inline def plain[V <: String & Singleton](inline field: String, inline value: V): String =
    inline constValueOpt[V] match
      case Some(_) =>
        inline if constValue[Matches[V, Plain]] then value
        else error("not a valid " + field + ": " + codeOf(value))
      case None    =>
        error("a " + field + " built this way has to be a string literal; use `.from` for a run-time value")

  /** Accepts `value` if every `/`-separated part of it can stand alone as one path segment, and fails the compilation
    * if any part cannot.
    *
    * @param field
    *   the field name to name in the compile error, matching the one [[PathSegment.segmented]] reports at run time
    */
  inline def segmented[V <: String & Singleton](inline field: String, inline value: V): String =
    inline constValueOpt[V] match
      case Some(_) =>
        inline if constValue[Matches[V, Segmented]] then value
        else error("not a valid " + field + ": " + codeOf(value))
      case None    =>
        error("a " + field + " built this way has to be a string literal; use `.from` for a run-time value")
