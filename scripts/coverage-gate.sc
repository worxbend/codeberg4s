//> using scala 3.8.4
//> using dep org.scala-lang.modules::scala-xml:2.4.0

// scripts/coverage-gate.sc — statement/branch coverage thresholds (PLAN.md §6.1).
//
// Reads the scoverage XML report Mill writes for each module and fails the
// build when a module is below its floor.
//
//   mill modules.domain.scoverage.xmlReport
//     -> out/modules/domain/scoverage/xmlReport.dest/scoverage.xml
//
// Usage (this is how verify.sh calls it):
//
//   scala-cli run scripts/coverage-gate.sc -- modules.domain modules.core modules.codec
//
// Arguments are Mill module selectors ("modules.domain") or bare leaf names
// ("domain"); both resolve to the same report. Set SCOVERAGE_OUT to point at a
// different Mill output root (default "out") — used by the script's own tests.
//
// The numbers come straight off the report's root element:
//
//   <scoverage statement-count=".." statements-invoked=".."
//              statement-rate="75.05" branch-rate="71.49" ...>
//
// so they are scoverage's own definitions of statement and branch coverage, not
// a re-derivation. Branch coverage is measured over statements the compiler
// plugin marked branch="true".
//
// Exit codes: 0 all modules pass, 1 a threshold was breached, 2 a report was
// missing or unreadable. A missing report is a failure on purpose — a coverage
// gate that skips silently is a false green.

import scala.util.{Try, Using}
import scala.xml.{Elem, XML}

import java.nio.file.{Files, Path, Paths}

/** Coverage floor for one module, in percent. */
final case class Floor(statement: Double, branch: Double)

/** PLAN.md §6.1: >= 90% statement / >= 85% branch on domain + core + codec; transport is measured but gated at 80%
  * because only stub-reachable paths are exercised without a live server.
  */
val DefaultFloor: Floor = Floor(statement = 90.0, branch = 85.0)

val FloorByModule: Map[String, Floor] = Map(
  "domain"    -> DefaultFloor,
  "core"      -> DefaultFloor,
  "codec"     -> DefaultFloor,
  "transport" -> Floor(statement = 80.0, branch = 80.0),
  "client"    -> Floor(statement = 80.0, branch = 80.0),
)

val OutRoot: Path = Paths.get(sys.env.getOrElse("SCOVERAGE_OUT", "out"))

/** "modules.domain" -> "domain"; "domain" -> "domain". */
def leafOf(selector: String): String =
  selector.split('.').filter(_.nonEmpty).lastOption.getOrElse(selector)

/** "modules.domain" -> out/modules/domain/scoverage/xmlReport.dest/scoverage.xml */
def reportPath(selector: String): Path =
  val segments = selector.split('.').filter(_.nonEmpty)
  segments
    .foldLeft(OutRoot)(_.resolve(_))
    .resolve("scoverage")
    .resolve("xmlReport.dest")
    .resolve("scoverage.xml")

final case class Measured(
    module: String,
    statementCount: Int,
    statementsInvoked: Int,
    statementRate: Double,
    branchRate: Double,
    floor: Floor,
):
  def statementOk: Boolean = statementRate >= floor.statement
  def branchOk: Boolean    = branchRate >= floor.branch
  def ok: Boolean          = statementOk && branchOk

def attrDouble(root: Elem, name: String): Double =
  root.attribute(name).flatMap(_.headOption).map(_.text.trim).flatMap(t => Try(t.toDouble).toOption).getOrElse(0.0)

def attrInt(root: Elem, name: String): Int =
  root.attribute(name).flatMap(_.headOption).map(_.text.trim).flatMap(t => Try(t.toInt).toOption).getOrElse(0)

def measure(selector: String): Either[String, Measured] =
  val path = reportPath(selector)
  if !Files.isRegularFile(path) then
    Left(
      s"no scoverage report at $path\n" +
        s"    run: ./mill $selector.scoverage.xmlReport  (after the test run that produced the measurements)"
    )
  else
    Using(Files.newInputStream(path))(XML.load).toEither.left
      .map(t => s"could not parse $path: ${t.getMessage}")
      .map: root =>
        val leaf            = leafOf(selector)
        Measured(
          module            = leaf,
          statementCount    = attrInt(root, "statement-count"),
          statementsInvoked = attrInt(root, "statements-invoked"),
          statementRate     = attrDouble(root, "statement-rate"),
          branchRate        = attrDouble(root, "branch-rate"),
          floor             = FloorByModule.getOrElse(leaf, DefaultFloor),
        )

def pct(value: Double): String = f"$value%6.2f%%"

def verdict(value: Double, floor: Double): String =
  if value >= floor then s"${pct(value)} >= ${floor.toInt}%  ok" else s"${pct(value)} <  ${floor.toInt}%  FAIL"

// --------------------------------------------------------------------------

val selectors: Seq[String] =
  if args.nonEmpty then args.toSeq else Seq("modules.domain", "modules.core", "modules.codec")

val results: Seq[(String, Either[String, Measured])] = selectors.map(s => s -> measure(s))

val missing: Seq[(String, String)] = results.collect { case (s, Left(err)) => s -> err }
val measured: Seq[Measured]        = results.collect { case (_, Right(m)) => m }

println()
println("  coverage gate — thresholds from PLAN.md §6.1")
println(f"  ${"module"}%-12s ${"stmts"}%13s   ${"statement"}%-24s ${"branch"}%-24s")
println("  " + "-" * 78)

measured.foreach: m =>
  val counts    = s"${m.statementsInvoked}/${m.statementCount}"
  val statement = verdict(m.statementRate, m.floor.statement)
  val branch    = verdict(m.branchRate, m.floor.branch)
  println(f"  ${m.module}%-12s $counts%13s   $statement%-24s $branch%-24s")

println()

if missing.nonEmpty then
  missing.foreach { case (selector, err) => Console.err.println(s"  [$selector] $err") }
  Console.err.println()
  Console.err.println(s"  coverage gate: ${missing.size} module report(s) unavailable")
  sys.exit(2)

val breaches = measured.filterNot(_.ok)
if breaches.nonEmpty then
  breaches.foreach: m =>
    val what =
      Seq(
        Option.when(!m.statementOk)(f"statement ${m.statementRate}%.2f%% < ${m.floor.statement}%.0f%%"),
        Option.when(!m.branchOk)(f"branch ${m.branchRate}%.2f%% < ${m.floor.branch}%.0f%%"),
      ).flatten.mkString(", ")
    Console.err.println(s"  ${m.module}: $what")
  Console.err.println()
  Console.err.println(s"  coverage gate FAILED for ${breaches.size} of ${measured.size} module(s)")
  sys.exit(1)

println(s"  coverage gate passed for ${measured.size} module(s)")
