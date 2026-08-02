//> using scala 3.8.4
//> using dep org.scala-lang.modules::scala-xml:2.4.0

// scripts/crap.sc — CRAP (Change Risk Anti-Patterns) per method, PLAN.md §6.4.
//
//   CRAP(m) = comp(m)^2 * (1 - cov(m))^3 + comp(m)
//
// where cov(m) is the fraction of m's statements that were executed and comp(m)
// is m's cyclomatic complexity. Any method above CRAP_MAX (30) fails the gate.
//
// Usage (this is how verify.sh calls it):
//
//   scala-cli run scripts/crap.sc                       # domain, core, codec
//   scala-cli run scripts/crap.sc -- modules.transport  # explicit selectors
//
// Reads the same reports as scripts/coverage-gate.sc:
//   out/<selector-as-path>/scoverage/xmlReport.dest/scoverage.xml
// Set SCOVERAGE_OUT to override the Mill output root.
//
// ---------------------------------------------------------------------------
// HONEST NOTE ON COMPLEXITY — THIS IS A PROXY, NOT A REAL CFG ANALYSIS
// ---------------------------------------------------------------------------
// There is no cyclomatic-complexity tool for Scala 3 in this build, and walking
// the sources would need a second parser that could drift from what scoverage
// actually instrumented. So complexity is derived from the coverage report
// itself:
//
//   comp(m) = max(1, number of statements in m with branch="true")
//
// The scoverage compiler plugin marks one statement branch="true" per arm of an
// `if`, a `match` case, a `try`/`catch` handler and similar. For those shapes
// the arm count equals the cyclomatic number (n decision outcomes = n paths), so
// the proxy is exact for the constructs that dominate this codebase. Where it is
// wrong it is wrong in known directions:
//
//   * UNDER-counts boolean short-circuits (`a && b` inside one condition is one
//     instrumented statement, two real paths) and guard clauses folded into a
//     pattern.
//   * ATTRIBUTES nested lambdas and local defs to their enclosing method, which
//     inflates the enclosing method and hides the inner one.
//   * Treats a method scoverage never instrumented (fully inlined, synthetic,
//     or `@nowarn`-suppressed) as complexity 1.
//
// Read the numbers as a ranking of risk, not as a certified metric. A method
// near the limit deserves a human look before the threshold is argued with.
// ---------------------------------------------------------------------------
//
// Exit codes: 0 nothing above the limit, 1 at least one method above it,
// 2 a report was missing or unreadable.

import scala.util.{Try, Using}
import scala.xml.{Elem, Node, XML}

import java.nio.file.{Files, Path, Paths}

/** Fail the build above this. PLAN.md §6.4 and §10. */
val CrapMax: Double = 30.0

/** Methods at or above this are printed even when the gate passes, so a slow drift towards the limit is visible in the
  * log.
  */
val ReportFloor: Double = 10.0

/** How many rows to print at most. */
val MaxRows: Int = 25

val OutRoot: Path = Paths.get(sys.env.getOrElse("SCOVERAGE_OUT", "out"))

def reportPath(selector: String): Path =
  selector
    .split('.')
    .filter(_.nonEmpty)
    .foldLeft(OutRoot)(_.resolve(_))
    .resolve("scoverage")
    .resolve("xmlReport.dest")
    .resolve("scoverage.xml")

def leafOf(selector: String): String =
  selector.split('.').filter(_.nonEmpty).lastOption.getOrElse(selector)

def attr(node: Node, name: String): Option[String] =
  node.attribute(name).flatMap(_.headOption).map(_.text.trim)

final case class MethodMetrics(
    module: String,
    name: String,
    file: String,
    line: Int,
    statements: Int,
    invoked: Int,
    complexity: Int,
):
  def coverage: Double = if statements == 0 then 1.0 else invoked.toDouble / statements.toDouble

  def crap: Double =
    val c = complexity.toDouble
    val u = 1.0 - coverage
    c * c * u * u * u + c

/** scoverage names methods "pkg/Class/method"; render the tail for the table. */
def shortName(raw: String): String =
  val parts = raw.split('/').filter(_.nonEmpty)
  if parts.length >= 2 then parts.takeRight(2).mkString(".") else raw

def parse(selector: String): Either[String, Seq[MethodMetrics]] =
  val path = reportPath(selector)
  if !Files.isRegularFile(path) then
    Left(
      s"no scoverage report at $path\n" +
        s"    run: ./mill $selector.scoverage.xmlReport  (after the test run that produced the measurements)"
    )
  else
    Using(Files.newInputStream(path))(XML.load).toEither.left
      .map(t => s"could not parse $path: ${t.getMessage}")
      .map: (root: Elem) =>
        val module = leafOf(selector)
        for
          klass  <- root \\ "class"
          method <- klass \ "methods" \ "method"
        yield
          val statements = method \ "statements" \ "statement"
          val live       = statements.filter(s => attr(s, "ignored").contains("false"))
          val counted    = if live.isEmpty then statements else live
          val branches   = counted.count(s => attr(s, "branch").contains("true"))
          val invoked    = counted.count(s => attr(s, "invocation-count").exists(_ != "0"))
          MethodMetrics(
            module = module,
            name   = shortName(attr(method, "name").getOrElse("<unknown>")),
            file   = attr(klass, "filename").getOrElse("<unknown>"),
            line   = counted.flatMap(s => attr(s, "line")).flatMap(l => Try(l.toInt).toOption).minOption.getOrElse(0),
            statements = counted.size,
            invoked    = invoked,
            complexity = math.max(1, branches),
          )

// --------------------------------------------------------------------------

val selectors: Seq[String] =
  if args.nonEmpty then args.toSeq else Seq("modules.domain", "modules.core", "modules.codec")

val parsed  = selectors.map(s => s -> parse(s))
val missing = parsed.collect { case (s, Left(err)) => s -> err }
val methods = parsed.collect { case (_, Right(ms)) => ms }.flatten

if missing.nonEmpty then
  missing.foreach { case (selector, err) => Console.err.println(s"  [$selector] $err") }
  Console.err.println()
  Console.err.println(s"  CRAP: ${missing.size} module report(s) unavailable")
  sys.exit(2)

val ranked    = methods.sortBy(m => (-m.crap, m.module, m.name))
val offenders = ranked.filter(_.crap > CrapMax)
val shown     = ranked.filter(_.crap >= ReportFloor).take(MaxRows)

println()
println(f"  CRAP = comp^2 * (1 - cov)^3 + comp   (limit $CrapMax%.0f; complexity is a branch-count proxy — see header)")
println(f"  ${"module"}%-10s ${"method"}%-46s ${"cmpl"}%4s ${"cov"}%7s ${"CRAP"}%8s")
println("  " + "-" * 82)

if shown.isEmpty then println(f"  (no method reaches CRAP $ReportFloor%.0f — ${methods.size} methods measured)")
else
  shown.foreach: m =>
    val flag = if m.crap > CrapMax then " <<<" else ""
    println(
      f"  ${m.module}%-10s ${m.name.takeRight(46)}%-46s ${m.complexity}%4d ${m.coverage * 100}%6.1f%% ${m.crap}%8.1f$flag"
    )

println()

if offenders.nonEmpty then
  Console.err.println(f"  CRAP FAILED: ${offenders.size} method(s) above $CrapMax%.0f")
  offenders.foreach: m =>
    Console.err.println(
      f"    ${m.module}/${m.file}:${m.line}  ${m.name}  crap=${m.crap}%.1f  cmpl=${m.complexity}  cov=${m.coverage * 100}%.1f%%"
    )
  sys.exit(1)

println(
  f"  CRAP ok — ${methods.size} methods measured, worst ${ranked.headOption.map(_.crap).getOrElse(0.0)}%.1f, limit $CrapMax%.0f"
)
