//> using scala 3.8.4

// scripts/alloc-bench.sc — bytes allocated and wall-clock time per JSON decode operation.
//
// Usage (scripts/alloc-bench.sh is the entry point; it resolves the compiled
// codec classpath, which this script needs at COMPILE time and therefore cannot
// resolve for itself):
//
//   scripts/alloc-bench.sh                       # every operation
//   scripts/alloc-bench.sh timestamps repo       # only operations whose name contains one of these
//   scripts/alloc-bench.sh --rounds=11 --scale=4 # more rounds, four times the iterations
//
// or, when the classpath is already in $CP:
//
//   scala-cli run scripts/alloc-bench.sc --extra-jars "$CP" -- [filters]
//
// ---------------------------------------------------------------------------
// DELIBERATELY NOT WIRED INTO verify.sh
// ---------------------------------------------------------------------------
// This is a measurement tool, not a gate. A timing assertion in CI fails on a
// noisy runner and passes on a quiet one, which trains everybody to re-run the
// build until it goes green — the exact habit that makes a real regression
// invisible. Allocation counts are far steadier than times, so a gate on them
// would be more defensible one day, but it is not free either: escape analysis
// moves the number with the JIT's mood (see the limitations below), so such a
// gate would still be a threshold on JVM behaviour rather than on this code.
//
// Run this by hand before and after a change, quote both numbers in the commit
// message, and let a human read them.
//
// ---------------------------------------------------------------------------
// WHAT IS MEASURED
// ---------------------------------------------------------------------------
//   parse.page-50       Json.parse of a 50-repository page — the parse step alone
//   decode.page-50      Json.decode[Vector[RepositoryDto]] of the same page — the
//                       parse plus the assembly of 50 wide DTOs, end to end
//   decode.repo-wide    Json.decode[RepositoryDto] of one repository object
//   assemble.repo-wide  the same DTO built from an ALREADY-PARSED document, so the
//                       parse is excluded and only field lookup and assembly remain
//   decode.org-narrow   Json.decode[OrganizationDto] of one organisation object
//   assemble.org-narrow the same, already parsed
//   timestamps.parse    Timestamps.parse of a real timestamp lifted out of the
//                       repository fixture, with the answer stored where it
//                       cannot be optimised away — see "the sink" below
//   timestamps.parse-ea the same call with the answer thrown away, so that
//                       HotSpot's escape analysis is free to delete the Instant;
//                       whether it does depends on what else ran in the same JVM
//                       — see the profile-pollution limitation below
//   parse.ints-1000     Json.parse of a 1000-element array of integers
//   parse.bools-1000    Json.parse of a 1000-element array of booleans
//   baseline.noop       an operation that does nothing, driven through the same
//                       loop — that row is the harness measuring itself, and it
//                       must read 0.0 B/op or nothing above it is trustworthy
//
// The narrow and the wide DTO are measured apart on purpose. RepositoryDto
// models 63 fields of a 64-key object; OrganizationDto models 12 of a 12-key
// object. Anything that changes how a field is looked up — the Map that
// JsonFields wraps today — behaves differently at those two widths, and one
// average over a mixed payload would hide which way each of them moved.
//
// ---------------------------------------------------------------------------
// METHODOLOGY
// ---------------------------------------------------------------------------
// Allocation is read from com.sun.management.ThreadMXBean, an OpenJDK extension
// to the standard java.lang.management.ThreadMXBean:
//
//   getCurrentThreadAllocatedBytes — the running total of heap bytes this thread
//                                    has allocated since it started
//
// One measurement is: read the counter, run the operation N times in a
// tail-recursive loop, read the counter again, divide the difference by N. Wall
// clock is System.nanoTime around the same loop.
//
// Warm-up is three full rounds per operation, discarded, before seven measured
// rounds; both counts are adjustable (--warmup, --rounds, --scale). Three rounds
// is enough for HotSpot to reach its top compilation tier on every operation
// here, because even the smallest of them runs hundreds of thousands of times
// per round.
//
// The two reported figures are summarised differently on purpose:
//
//   B/op  is the MEDIAN of the measured rounds. Allocation is close to
//         deterministic once warm — the spread column normally reads "exact" —
//         and the median ignores the odd round that ran extra code.
//   ns/op is the FASTEST measured round, not the median. Wall clock on a shared
//         machine measures the machine as much as the code: a round that caught
//         a garbage collection, a background compilation or another process is
//         slower for a reason that has nothing to do with the operation, and
//         those reasons only ever add time. The fastest round is the least
//         contaminated estimate available without a quiet machine, and the
//         spread column beside it says how much the other rounds disagreed —
//         when that number is large, the machine was busy, not the code slow.
//
// Every operation answers an int checksum derived from its result, the loop adds
// those up, and the total is printed. Without that, nothing stops the JIT from
// deleting work whose result is never read.
//
// Before measuring anything the harness runs each operation once and refuses to
// continue if it answered a failure: a decode that fails bails out early and
// would report a fraction of the real cost as though it were the whole cost.
//
// THE SINK. An object that never leaves the method that made it can be taken
// apart by HotSpot's escape analysis and never allocated at all. That is a
// genuine saving when it happens in the library, and a measurement artefact when
// it happens only because a benchmark threw the answer away. Timestamps.parse is
// the case where it matters: measured with its answer discarded it allocates
// nothing, and in the library the Instant it returns is stored in a Repository
// and therefore does allocate. So the primary timestamp operation writes its
// answer into a one-element array allocated once, up front — the standard
// blackhole trick, and the smallest thing that makes the object escape. The
// paired -ea operation does not, so the gap between the two rows is the size of
// the effect rather than a claim to have avoided it.
//
// ---------------------------------------------------------------------------
// KNOWN LIMITATIONS — READ THESE BEFORE QUOTING A NUMBER
// ---------------------------------------------------------------------------
// THIS IS NOT JMH. It is a single-threaded allocation counter with a loop around
// it. It does not fork a JVM per benchmark, does not blackhole its results
// beyond the checksum, does not measure a distribution, does not report error
// bars, and cannot detect that a result was constant-folded. JMH exists and does
// all of that; if a number from here ever decides a design argument, reach for
// JMH rather than arguing about this script.
//
// More specifically:
//
//   * THE COUNTER IS PER THREAD. Work moved onto another thread — a parallel
//     collection, a future, an executor — allocates just as much and shows up
//     here as free. Everything measured below is synchronous today.
//
//   * ESCAPE ANALYSIS COUNTS AS A SAVING. An allocation HotSpot scalar-replaces
//     is never counted, which is right (it costs nothing at run time) and also
//     means the number depends on JIT state, on inlining decisions and on the
//     JDK build. Numbers from two different JDKs are not comparable. That is why
//     the JDK is printed in the report header, and why the two timestamp rows
//     are there: they are the same call measured either side of the effect.
//
//   * THE DRIVING CALL SITE IS MEGAMORPHIC. Every operation goes through one
//     virtual call in the loop, so an operation is never inlined into its
//     caller. That is representative for Json.decode, which is far too large for
//     any real caller to inline, and pessimistic for something as small as
//     Timestamps.parse, whose Instant might well be scalar-replaced at a real
//     call site and is counted here.
//
//   * EVERY OPERATION SHARES ONE JVM, so they pollute each other's profiles.
//     This is not hypothetical here, and the size of it was measured rather than
//     assumed: run on its own, timestamps.parse-ea reports 0.0 B/op, because
//     Timestamps.parse has one caller, gets inlined, and its Instant is scalar-
//     replaced. Run in the full table, where timestamps.parse calls it too, both
//     rows report 40.0 B/op — one Instant (24 B) plus one Some (16 B) — because
//     the shared method no longer inlines the same way. Forking a JVM per
//     benchmark is precisely what JMH does about this and what this script does
//     not. Compare a full table against a full table, never a filtered run
//     against an unfiltered one.
//
//   * TIMES ARE WALL CLOCK ON WHATEVER MACHINE THIS RAN ON, with no attempt to
//     pin a CPU, quiet the machine, or account for turbo and thermal drift. Read
//     ns/op as an order of magnitude and a direction of travel: the same row has
//     been seen to move by half between two consecutive full runs on an idle
//     laptop. Bytes are the number worth arguing about, and even they are not
//     perfectly reproducible across runs — the small operations have been
//     observed to move by 16 bytes, one object, from one run to the next. A
//     one-object difference at these sizes is noise, not a result.
//
//   * THE SPREAD COLUMNS say how far the measured rounds of ONE run disagreed.
//     "exact" means every round agreed to the byte. They say nothing about how
//     far two different runs would disagree; only running it again says that.
//
//   * ONLY HEAP BYTES ARE COUNTED. Direct byte buffers, memory-mapped files and
//     native allocation are invisible here.
//
// ---------------------------------------------------------------------------
// INPUTS — REAL PAYLOADS, WITH ONE DOCUMENTED EXCEPTION
// ---------------------------------------------------------------------------
// The page is built by repeating modules/codec/test/resources/golden/repository/
// repo-single.json fifty times inside one array. That file is a verbatim,
// byte-for-byte capture from the live Codeberg instance (3,404 bytes; see the
// golden MANIFEST), so the page has the field spellings, the null parent, the Go
// zero-time sentinels and the pretty-printed whitespace a real listing has. The
// organisation object is golden/organization/org-single.json, likewise verbatim.
// The timestamp is read out of the parsed repository rather than typed in here,
// so it cannot drift from the fixture.
//
// The two 1000-element scalar arrays are the exception: no captured fixture
// holds a scalar array anywhere near that long, so they are generated. They
// measure the array and number readers of JsonValue at a length where the
// per-element cost is visible, and their rows are labelled "synth".
//
// Exit codes: 0 measured, 2 could not measure — a missing fixture, a JVM without
// the allocation counter, an operation that failed its pre-flight, or a bad
// argument. There is no exit code 1: nothing here can breach a threshold,
// because there is no threshold.

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.organizations.wire.OrganizationDto
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto

import com.sun.management.ThreadMXBean

import scala.annotation.tailrec
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Try

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Reports the reason and gives up. Every failure here is "could not measure", never "measured something bad". */
def bail(reason: String): Nothing =
  Console.err.println()
  Console.err.println(s"  alloc-bench: $reason")
  sys.exit(2)

// --------------------------------------------------------------------------
// Arguments
// --------------------------------------------------------------------------

val Usage: String = "usage: scripts/alloc-bench.sh [--rounds=N] [--warmup=N] [--scale=F] [name-filter ...]"

val KnownFlags: Seq[String] = Seq("--rounds=", "--warmup=", "--scale=")

def flagValue(prefix: String): Option[String] =
  args.find(_.startsWith(prefix)).map(_.drop(prefix.length))

def intFlag(prefix: String, fallback: Int): Int =
  flagValue(prefix) match
    case None      => fallback
    case Some(raw) =>
      Try(raw.toInt).toOption
        .filter(_ > 0)
        .getOrElse(bail(s"$prefix expects a positive integer, got '$raw'\n    $Usage"))

def doubleFlag(prefix: String, fallback: Double): Double =
  flagValue(prefix) match
    case None      => fallback
    case Some(raw) =>
      Try(raw.toDouble).toOption
        .filter(_ > 0.0)
        .getOrElse(bail(s"$prefix expects a positive number, got '$raw'\n    $Usage"))

args.find(argument => argument.startsWith("--") && !KnownFlags.exists(argument.startsWith)) match
  case Some(unknown) => bail(s"unknown option '$unknown'\n    $Usage")
  case None          => ()

val rounds: Int          = intFlag("--rounds=", 7)
val warmup: Int          = intFlag("--warmup=", 3)
val scale: Double        = doubleFlag("--scale=", 1.0)
val filters: Seq[String] = args.filterNot(_.startsWith("--")).toSeq

// --------------------------------------------------------------------------
// Inputs
// --------------------------------------------------------------------------

/** Where the captured bodies live. Overridable so the harness can be pointed at a checkout elsewhere. */
val GoldenRoot: Path = Paths.get(sys.env.getOrElse("GOLDEN_ROOT", "modules/codec/test/resources/golden"))

def fixture(relative: String): String =
  val path = GoldenRoot.resolve(relative)
  if !Files.isRegularFile(path) then
    bail(s"missing golden fixture $path\n    run this from the repository root, or set GOLDEN_ROOT")
  else String(Files.readAllBytes(path), StandardCharsets.UTF_8)

/** Repository objects in the measured page. Fifty is one Forgejo listing page at the default limit. */
val PageRepetitions: Int = 50

/** Length of the two generated scalar arrays — the only inputs here that are not a capture. */
val ScalarArrayLength: Int = 1000

val repoSingle: String = fixture("repository/repo-single.json")
val orgSingle: String  = fixture("organization/org-single.json")

val page: String = Vector.fill(PageRepetitions)(repoSingle).mkString("[", ",", "]")

val integerArray: String = (0 until ScalarArrayLength).mkString("[", ",", "]")

val booleanArray: String =
  val alternating = (0 until ScalarArrayLength).map(index => if index % 2 == 0 then "true" else "false")
  alternating.mkString("[", ",", "]")

def parsedOrBail(label: String, body: String): JsonValue =
  Json.parse(body) match
    case Right(value)  => value
    case Left(failure) => bail(s"$label did not parse: ${failure.message}")

val repoValue: JsonValue = parsedOrBail("repo-single.json", repoSingle)
val orgValue: JsonValue  = parsedOrBail("org-single.json", orgSingle)

val repoDecoder: JsonDecoder[RepositoryDto]  = JsonDecoder[RepositoryDto]
val orgDecoder: JsonDecoder[OrganizationDto] = JsonDecoder[OrganizationDto]

/** A real timestamp, read out of the parsed fixture rather than written here, so it cannot drift from the capture. */
val timestamp: String =
  repoValue
    .field("updated_at")
    .flatMap(_.strOpt)
    .getOrElse(bail("repo-single.json carries no string updated_at to measure Timestamps.parse against"))

// --------------------------------------------------------------------------
// Operations
// --------------------------------------------------------------------------

/** One measurable operation.
  *
  * `run` answers an int checksum derived from the result, and a negative checksum means the operation failed. A
  * primitive int rather than the result itself is deliberate: a generic return type would box on every iteration and
  * add an allocation the harness would then charge to the code under test.
  *
  * @param name
  *   how the row is labelled, and what a filter argument matches against
  * @param input
  *   the size of what this operation consumes, for the report
  * @param baseIterations
  *   calls per round before `--scale` is applied, chosen so that a round takes roughly a tenth of a second
  */
abstract class Op(val name: String, val input: String, val baseIterations: Int):
  def run(): Int

  final def iterations: Int = math.max(1, (baseIterations * scale).toInt)

def sizeOf(text: String): String = f"${text.length}%,d B"

/** Length of an optional wire string, as a checksum contribution. Written out rather than `fold`ed because a generic
  * combinator would box the int on every iteration.
  */
def widthOf(value: Option[String]): Int = value match
  case Some(text) => text.length
  case None       => 0

val parsePage: Op = new Op("parse.page-50", sizeOf(page), 100):

  def run(): Int = Json.parse(page) match
    case Right(JsonValue.Arr(values)) => values.size
    case Right(_)                     => 0
    case Left(_)                      => -1

val decodePage: Op = new Op("decode.page-50", sizeOf(page), 60):

  def run(): Int = Json.decode[Vector[RepositoryDto]](page) match
    case Right(repositories) => repositories.size
    case Left(_)             => -1

val decodeWide: Op = new Op("decode.repo-wide", sizeOf(repoSingle), 3000):

  def run(): Int = Json.decode[RepositoryDto](repoSingle) match
    case Right(repository) => repository.topics.size
    case Left(_)           => -1

val assembleWide: Op = new Op("assemble.repo-wide", "parsed", 6000):

  def run(): Int = repoDecoder.decode(repoValue) match
    case Right(repository) => repository.topics.size
    case Left(_)           => -1

val decodeNarrow: Op = new Op("decode.org-narrow", sizeOf(orgSingle), 20000):

  def run(): Int = Json.decode[OrganizationDto](orgSingle) match
    case Right(organisation) => widthOf(organisation.name)
    case Left(_)             => -1

val assembleNarrow: Op = new Op("assemble.org-narrow", "parsed", 40000):

  def run(): Int = orgDecoder.decode(orgValue) match
    case Right(organisation) => widthOf(organisation.name)
    case Left(_)             => -1

/** Where the escaping operations put their answer, so that HotSpot cannot prove the object dies in `run` and delete it.
  *
  * Allocated once, before any measurement, and never read: a store into it is a card mark and nothing else. This is the
  * one piece of mutable state in the harness and it exists because JMH's blackholes are not available here.
  */
val sink: Array[AnyRef] = new Array[AnyRef](1)

val parseTimestamp: Op = new Op("timestamps.parse", sizeOf(timestamp), 300000):

  def run(): Int =
    val parsed = Timestamps.parse(timestamp)
    sink(0) = parsed
    parsed match
      case Some(instant) => instant.getNano
      case None          => -1

val parseTimestampDiscarded: Op = new Op("timestamps.parse-ea", sizeOf(timestamp), 300000):

  def run(): Int = Timestamps.parse(timestamp) match
    case Some(instant) => instant.getNano
    case None          => -1

val parseIntegers: Op = new Op("parse.ints-1000", s"${sizeOf(integerArray)} synth", 2000):

  def run(): Int = Json.parse(integerArray) match
    case Right(JsonValue.Arr(values)) => values.size
    case Right(_)                     => 0
    case Left(_)                      => -1

val parseBooleans: Op = new Op("parse.bools-1000", s"${sizeOf(booleanArray)} synth", 2000):

  def run(): Int = Json.parse(booleanArray) match
    case Right(JsonValue.Arr(values)) => values.size
    case Right(_)                     => 0
    case Left(_)                      => -1

val noop: Op = new Op("baseline.noop", "—", 2000000):
  def run(): Int = 1

val operations: Vector[Op] =
  Vector(
    parsePage,
    decodePage,
    decodeWide,
    assembleWide,
    decodeNarrow,
    assembleNarrow,
    parseTimestamp,
    parseTimestampDiscarded,
    parseIntegers,
    parseBooleans,
    noop,
  )

val selected: Vector[Op] =
  if filters.isEmpty then operations
  else operations.filter(operation => filters.exists(filter => operation.name.contains(filter)))

if selected.isEmpty then
  bail(s"no operation matches ${filters.mkString(", ")}\n    known: ${operations.map(_.name).mkString(", ")}")

// --------------------------------------------------------------------------
// Measurement
// --------------------------------------------------------------------------

val threads: ThreadMXBean = ManagementFactory.getThreadMXBean match
  case bean: ThreadMXBean => bean
  case other              =>
    bail(
      s"this JVM's ThreadMXBean is ${other.getClass.getName}, not the com.sun.management extension\n" +
        "    the harness needs getCurrentThreadAllocatedBytes, which is an OpenJDK/HotSpot extension"
    )

if !threads.isThreadAllocatedMemorySupported then bail("this JVM does not support per-thread allocation counting")

if !threads.isThreadAllocatedMemoryEnabled then threads.setThreadAllocatedMemoryEnabled(true)

/** What one round of one operation cost. `checksum` exists so that the work cannot be proved dead. */
final case class Round(bytes: Long, nanos: Long, checksum: Int)

@tailrec
def drive(operation: Op, remaining: Int, checksum: Int): Int =
  if remaining <= 0 then checksum else drive(operation, remaining - 1, checksum + operation.run())

def measure(operation: Op, iterations: Int): Round =
  val startBytes = threads.getCurrentThreadAllocatedBytes
  val startNanos = System.nanoTime()
  val checksum   = drive(operation, iterations, 0)
  val endNanos   = System.nanoTime()
  val endBytes   = threads.getCurrentThreadAllocatedBytes
  Round(endBytes - startBytes, endNanos - startNanos, checksum)

/** Two back-to-back reads of the counter. Anything but zero means the reads themselves allocate, and every row below
  * carries that much noise per round.
  */
val counterOverhead: Long =
  val before = threads.getCurrentThreadAllocatedBytes
  val after  = threads.getCurrentThreadAllocatedBytes
  after - before

def median(values: Vector[Long]): Double =
  val sorted = values.sorted
  val size   = sorted.length
  if size % 2 == 1 then sorted(size / 2).toDouble
  else (sorted(size / 2 - 1) + sorted(size / 2)).toDouble / 2.0

/** How far apart the measured rounds were, as a percentage of their median. "exact" means they agreed to the byte. */
def spreadOf(values: Vector[Long]): String =
  if values.min.equals(values.max) then "exact"
  else f"${(values.max - values.min).toDouble / math.max(1.0, median(values)) * 100.0}%.1f%%"

final case class Result(operation: Op, iterations: Int, warmChecksum: Int, rounds: Vector[Round]):
  def bytesPerOp: Double = median(rounds.map(_.bytes)) / iterations.toDouble
  def nanosPerOp: Double = rounds.map(_.nanos).min.toDouble / iterations.toDouble
  def checksum: Int      = warmChecksum + rounds.map(_.checksum).sum
  def byteSpread: String = spreadOf(rounds.map(_.bytes))
  def nanoSpread: String = spreadOf(rounds.map(_.nanos))

def profile(operation: Op): Result =
  val iterations = operation.iterations
  // The warm-up checksum is kept and printed for the same reason the measured one is: so that none of it is dead code.
  val warmed     = (1 to warmup).map(_ => drive(operation, iterations, 0)).sum
  sys.runtime.gc()
  Result(operation, iterations, warmed, (1 to rounds).map(_ => measure(operation, iterations)).toVector)

// A failing operation does a fraction of the work and would be reported as though it had done all of it.
selected.foreach: operation =>
  if operation.run() < 0 then
    bail(s"${operation.name} answered a failure on its pre-flight run — the harness would time the failure path")

// --------------------------------------------------------------------------
// Report
// --------------------------------------------------------------------------

def amount(value: Double): String = if value >= 1000.0 then f"$value%,.0f" else f"$value%.1f"

def commit: String =
  Try(Process(Seq("git", "rev-parse", "--short", "HEAD")).!!(ProcessLogger(_ => ())).trim)
    .filter(_.nonEmpty)
    .getOrElse("unknown commit")

val jvm: String = s"${sys.props.getOrElse("java.vm.name", "?")} ${sys.props.getOrElse("java.runtime.version", "?")}"

println()
println("  allocation harness — heap bytes this thread allocated per operation, and wall-clock time")
println(s"  at $commit · $jvm")
println(s"  $rounds measured round(s) after $warmup warm-up round(s) · iteration scale $scale")
println("  B/op is the median of those rounds, ns/op the fastest of them — see the header for why they differ")
println("  NOT JMH, NOT A GATE — read the header of scripts/alloc-bench.sc before quoting a number")
println()
println(
  f"  ${"operation"}%-20s ${"input"}%-15s ${"iters"}%9s " +
    f"${"B/op"}%12s ${"B spread"}%9s ${"ns/op best"}%12s ${"ns spread"}%10s"
)
println("  " + "-" * 95)

val results: Vector[Result] = selected.map(profile)

results.foreach: result =>
  println(
    f"  ${result.operation.name}%-20s ${result.operation.input}%-15s ${result.iterations}%,9d " +
      f"${amount(result.bytesPerOp)}%12s ${result.byteSpread}%9s " +
      f"${amount(result.nanosPerOp)}%12s ${result.nanoSpread}%10s"
  )

println()
println(f"  counter overhead between two back-to-back reads: $counterOverhead%,d B (zero is the expected answer)")
println(s"  checksum ${results.map(_.checksum).sum} — printed so the JIT cannot prove the measured work is dead")
println()
