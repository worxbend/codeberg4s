# Resolved Toolchain and Dependency Versions

Every version in this repository is **pinned**, never floated. This file records
what is pinned, where it is pinned, and how it was resolved — so a later reader
can tell a deliberate pin from a stale one.

| | |
| --- | --- |
| Resolved on | `2026-08-09` |
| Resolver | `https://repo1.maven.org/maven2` (`maven-metadata.xml`) |
| Authority for build pins | [`build.mill`](../build.mill) — this document mirrors it and must never contradict it |

> This document is **derived**. `build.mill`, `.mill-version`, `.scalafmt.conf`
> and `.scalafix.conf` are the sources of truth. If they disagree with this file,
> they win and this file is stale.

---

## 1. Resolution method (CLAUDE.md, SCALA_CODE_STYLE.md § Tooling)

The method is fixed and not a matter of preference:

1. **Discovery by name** — `https://index.scala-lang.org/api/autocomplete?q=<name>`
   to find candidate coordinates.
2. **Authoritative resolution** — read
   `https://repo1.maven.org/maven2/<group-with-slashes>/<artifact>_3/maven-metadata.xml`
   and take the last **stable** `<version>`.
3. **Stability filter** — skip any version matching `RC`, `M<n>`, `SNAP`,
   `alpha`, `beta`, `NIGHTLY` unless that pre-release is specifically wanted.
4. **Forbidden** — `search.maven.org/solrsearch`. Its index runs months stale and
   has produced wrong answers on this project's dependency set before.

Re-check with:

```bash
mill mill.scalalib.Dependency/showUpdates
```

Verification performed for this document — each artifact's
`maven-metadata.xml` was fetched directly and its newest stable version compared
against the pin in `build.mill`.

---

## 2. Language and build

| Component | Pinned | Pinned in | Latest stable | Status |
| --- | --- | --- | --- | --- |
| Scala | `3.8.4` | `build.mill` → `Versions.scala` | `3.8.4` | current |
| Mill | `1.1.7` | `.mill-version` (committed) | `1.1.7` (`com.lihaoyi:mill-dist`) | current |
| `mill-contrib-scoverage` | `1.1.7` | `build.mill` header `//| mvnDeps:` | `1.1.7` | current — must track the Mill version exactly |

The `./mill` launcher script carries `DEFAULT_MILL_VERSION="1.1.7"`, matching
`.mill-version`, which overrides it anyway. The launcher default only applies to
a checkout with no `.mill-version` at all; it used to name `1.1.6-104-5bbe1e`,
an untagged snapshot 104 commits past the `1.1.6` tag, which is not a version
anyone can reason about.

`.mill-checksums` records the SHA-256 of every Mill distribution the launcher is
allowed to run — one line per platform, because the launcher picks a different
native binary per OS and architecture. `./mill` checks the file it is about to
execute against that digest on every run, whether it was downloaded a moment ago
or cached months ago, and refuses to run anything unlisted. Bumping Mill
therefore means editing two files: `.mill-version` and `.mill-checksums`. The
header of `.mill-checksums` carries the exact commands for regenerating it.

**Deviation from SCALA_CODE_STYLE.md.** That guide's pinned-versions table names
Mill `0.12.x` and Ox `1.0.6`. Both are superseded here by decisions recorded in
the lane brief:

- Mill is `1.1.7`, not `0.12.x`, with the `modules/<name>/src` layout.
- **There is no Ox dependency.** The public API is `Future`-based per PLAN.md
  §3.2. SCALA_CODE_STYLE.md governs everything else, but its Ox concurrency
  chapter does not apply to this build.

## 3. Runtime dependencies (published artifact surface)

Every one of these ends up in the consumer's classpath, so the list is
deliberately short (PLAN.md ADR-2, ADR-3).

| Dependency | Coordinate | Pinned | Latest stable | Status | Module |
| --- | --- | --- | --- | --- | --- |
| sttp client4 core | `com.softwaremill.sttp.client4::core` | `4.0.26` | `4.0.26` | current | `transport` |
| sttp-model core | `com.softwaremill.sttp.model::core` | `1.7.18` | `1.7.18` | current | `transport` |
| jsoniter-scala core | `com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core` | `2.40.1` | `2.40.1` | current | `codec` |

Three artifacts, which is what README.md's "sttp client4 and jsoniter-scala,
that is the list" claims. `jsoniter-scala-macros` used to be a fourth. It is the
artifact that *derives* a codec from a case class at compile time, and this
build derives none — `modules/codec` hand-writes one `JsonValueCodec` over a
document model instead, for the reasons in `docs/HAZARDS.md` §1. Nothing under
`modules/` imports anything outside `jsoniter_scala.core`, so the macros jar was
about a megabyte of dead weight on every consumer's classpath.

`modules/domain` and `modules/core` declare **no** `mvnDeps` at all — the
hexagonal boundary is enforced by the build graph, not by convention
(PLAN.md §3.1).

**JSON library:** PLAN.md ADR-3 originally selected upickle. The project now
uses **jsoniter-scala**, and `build.mill` pins it; upickle and its `ujson`
document model are gone from the build and from the sources. Nothing in this
repository has a `ReadWriter` — that is upickle's codec type, and the
jsoniter-scala equivalent is `JsonValueCodec[A]`, from
`com.github.plokhotnyuk.jsoniter_scala.core`.

SCALA_CODE_STYLE.md's "JSON Codecs" section is written for the same library, so
its vocabulary is this repository's vocabulary. Its *mechanism* is not: the
examples there call `JsonCodecMaker.make` to derive a `JsonValueCodec` per DTO
at compile time, and that macro lives in `jsoniter-scala-macros`, which this
build deliberately does not depend on (see the paragraph above). The reason is
in `docs/HAZARDS.md` §1 — no response definition in the pinned spec declares
`required`, `nullable` never appears, and live payloads send JSON `null` where
the spec promises an array or an object. A derived codec answers a payload like
that by failing.

What `modules/codec` does instead is parse once into a document model,
`JsonValue`, whose single hand-written `JsonValueCodec[JsonValue]` is the only
codec in the build, and then assemble each DTO from that document. So the rule
SCALA_CODE_STYLE.md's example illustrates — *a top-level JSON array needs a
codec too, not only its element type* — is satisfied structurally rather than
per type: `JsonValue.Arr` is a case of the same model, so a list body and an
object body are decoded by the one codec and there is no per-DTO codec that
could be forgotten.

## 4. Test dependencies (not published)

| Dependency | Coordinate | Pinned | Latest stable | Status |
| --- | --- | --- | --- | --- |
| munit | `org.scalameta::munit` | `1.3.5` | `1.3.5` | current |
| munit-scalacheck | `org.scalameta::munit-scalacheck` | `1.3.0` | `1.3.0` | current |
| ScalaCheck | `org.scalacheck::scalacheck` | `1.19.0` | `1.19.0` | current |
| testcontainers-scala-munit | `com.dimafeng::testcontainers-scala-munit` | `0.44.1` | `0.44.1` | current |

`munit` and `munit-scalacheck` version independently — `1.3.5` and `1.3.0` are
both the newest stable of their own artifact, not a mismatch.

testcontainers-scala is confined to `modules/it`, the environmentally unsuitable
boundary (PLAN.md §6.5). It is excluded from coverage, mutation, and the default
test sweep.

## 5. Quality tooling

| Tool | Pinned | Pinned in | Latest stable | Status |
| --- | --- | --- | --- | --- |
| scoverage | `2.5.2` | `build.mill` → `Versions.scoverage` | `2.5.2` | current |
| Scalafmt | `3.11.5` | `.scalafmt.conf` → `version` | `3.11.5` | current |
| Scalafix | via Mill's `__.fix` | `.scalafix.conf` (rules only, no version) | `scalafix-core` `0.14.7` | resolved transitively by Mill |
| Stryker4s | not yet wired | — | — | scaffold only when the task calls for it (CLAUDE.md § Quality analysis) |

### scoverage `2.5.2`

For Scala 3.4+ the coverage instrumentation lives in the compiler itself; Mill's
`ScoverageModule` resolves `org.scoverage::scalac-scoverage-reporter` (and its
`-serializer` / `-domain` siblings) at `scoverageVersion`. Those artifacts read
the compiler's output and turn it into the XML and HTML reports, so a bump here
changes reporting, not instrumentation.

Taken from `2.3.0` on 2026-08-09. `2.4.0` is the only release in that range with
a breaking change, and it does not touch this build: it drops support for Scala
2.13.15-and-earlier and 2.12.16, and this project is Scala 3 only. `2.4.1` fixes
instrumentation of pattern-matching assignments, `2.4.2` and `2.5.1` add Scala 2
versions, `2.5.0` is dependency updates, and `2.5.2` adds incremental coverage.
The measured line and branch percentages in `verify.sh`'s coverage gate were
identical before and after the bump.

### Scalafmt `3.11.5`

Taken from `3.11.4` on 2026-08-09, in a standalone `style:` commit as the rule
below requires: `align.preset = most` means a formatter bump *can* realign the
whole repository, and that churn must never share a commit with a logic or
dependency change.

In the event it realigned nothing. `mill mill.scalalib.scalafmt/` under `3.11.5`
rewrote 0 of 850 files, so the only line in that commit's diff outside the
documentation is the `version` key itself. The 3.11.5 changes are a website
migration and four fixes — inverted offsets on empty trees, a CLI error that
could mask a real one, the runner reporting which failure it exited on, and a
`RemoveScala3OptionalBraces` brace/colon oscillation — none of which this
configuration triggers.

That zero-file result is also what re-verifies the longest-match claim in
`.scalafmt.conf`'s `rewrite.imports.groups` comment: if 3.11.5 had changed how
an import is assigned to a group, `scala.*` imports across the tree would have
moved and the reformat would not have been a no-op.

## 6. Not adopted

| Candidate | Decision | Reason |
| --- | --- | --- |
| Ox | **not a dependency** | Public API is `Future`-based (PLAN.md §3.2). Overrides SCALA_CODE_STYLE.md's Ox chapter for this repo. |
| cats-effect / ZIO | rejected | PLAN.md ADR-2 (docs/adr/0002) — hand-rolled `Exec[F]` keeps the published dependency footprint at the three artifacts in §3. |
| circe | rejected | docs/adr/0003 — a larger dependency, and its optics would not change the shape of the problem the document model in `modules/codec` solves. |
| upickle / ujson | **removed** | PLAN.md ADR-3 chose it and the first docs/adr/0003 confirmed it; the current docs/adr/0003 supersedes both and the code moved to jsoniter-scala. No `upickle` or `ujson` import remains anywhere under `modules/`. |
| jsoniter-scala-macros | rejected | docs/adr/0003 — `JsonCodecMaker` derives a codec per DTO, and this build derives none. Mill's `mvnDeps` is runtime scope too, so declaring it would put roughly a megabyte of derivation machinery on every consumer's classpath. |
| softwaremill/retry | **undecided** | PLAN.md ADR-4 evaluates it at Phase 2; not pinned in `build.mill` yet. If its `odelay` dependency or maintenance status disqualifies it, `RetryPolicy` is implemented in `core` with no new dependency. Decide before Phase 2 Track A, and record the outcome here. |
| quicklens | not pinned | PLAN.md §0 mentions it as a candidate SoftwareMill utility; no module needs it yet. |

## 7. Version-bump procedure

1. Re-resolve from `repo1.maven.org` per §1 — never from a search index, never
   from memory.
2. One dependency per commit: `build(deps): bump <artifact> to <version>`.
3. `./mill __.compile` must stay at zero warnings (`-Werror` is on), then
   `./mill __.test`.
4. A Scalafmt bump is a `style:` commit of its own, with the whole tree
   reformatted in that same commit and no logic changes.
5. Update this file in the same commit as the bump, so it never drifts from
   `build.mill`.
