# Resolved Toolchain and Dependency Versions

Every version in this repository is **pinned**, never floated. This file records
what is pinned, where it is pinned, and how it was resolved — so a later reader
can tell a deliberate pin from a stale one.

| | |
| --- | --- |
| Resolved on | `2026-08-01` |
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

The `./mill` launcher script carries `DEFAULT_MILL_VERSION="1.1.6-104-5bbe1e"`,
but `.mill-version` is present and overrides it, so the effective version is
`1.1.7`. The launcher default is a bootstrap fallback only.

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
| jsoniter-scala core | `com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core` | `2.39.1` | `2.39.1` | current | `codec` |
| jsoniter-scala macros | `com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros` | `2.39.1` | `2.39.1` | current | `codec` |

`modules/domain` and `modules/core` declare **no** `mvnDeps` at all — the
hexagonal boundary is enforced by the build graph, not by convention
(PLAN.md §3.1).

**JSON library:** PLAN.md §3.3 originally selected upickle; the project now uses **jsoniter-scala**, and
`build.mill` pins it. SCALA_CODE_STYLE.md's "JSON Codecs" section shows
jsoniter-scala examples; those examples do not apply to this repository. The rule
they illustrate — *derive the codec on the DTO, next to the DTO, and provide a
codec for the list type as well as the element type* — does apply, translated to
jsoniter-scala `ReadWriter`s.

## 4. Test dependencies (not published)

| Dependency | Coordinate | Pinned | Latest stable | Status |
| --- | --- | --- | --- | --- |
| munit | `org.scalameta::munit` | `1.3.4` | `1.3.4` | current |
| munit-scalacheck | `org.scalameta::munit-scalacheck` | `1.3.0` | `1.3.0` | current |
| ScalaCheck | `org.scalacheck::scalacheck` | `1.19.0` | `1.19.0` | current |
| testcontainers-scala-munit | `com.dimafeng::testcontainers-scala-munit` | `0.44.1` | `0.44.1` | current |

`munit` and `munit-scalacheck` version independently — `1.3.4` and `1.3.0` are
both the newest stable of their own artifact, not a mismatch.

testcontainers-scala is confined to `modules/it`, the environmentally unsuitable
boundary (PLAN.md §6.5). It is excluded from coverage, mutation, and the default
test sweep.

## 5. Quality tooling

| Tool | Pinned | Pinned in | Latest stable | Status |
| --- | --- | --- | --- | --- |
| scoverage | `2.3.0` | `build.mill` → `Versions.scoverage` | `2.5.2` | **behind — see below** |
| Scalafmt | `3.11.4` | `.scalafmt.conf` → `version` | `3.11.5` | **one patch behind — see below** |
| Scalafix | via Mill's `__.fix` | `.scalafix.conf` (rules only, no version) | `scalafix-core` `0.14.7` | resolved transitively by Mill |
| Stryker4s | not yet wired | — | — | scaffold only when the task calls for it (CLAUDE.md § Quality analysis) |

### scoverage `2.3.0` vs `2.5.2`

For Scala 3.4+ the coverage instrumentation lives in the compiler itself; Mill's
`ScoverageModule` resolves `org.scoverage::scalac-scoverage-serializer` at
`scoverageVersion`. `2.3.0` is a real published version of that artifact
(confirmed against `maven-metadata.xml`), so the build is valid — it is simply
not the newest. Bumping to `2.5.2` is a one-line `build.mill` change and belongs
in its own `build(deps):` commit per CLAUDE.md's granularity rule. **Not changed
by this lane**, which owns only `spec/` and `docs/`.

### Scalafmt `3.11.4` vs `3.11.5`

`.scalafmt.conf` pins `3.11.4`. The newest stable `org.scalameta:scalafmt-core`
is `3.11.5`. SCALA_CODE_STYLE.md requires the pin to match the installed binary,
and `align.preset = most` means a version bump can produce a repository-wide
realignment diff. If bumped, it must land as a standalone `style:` commit with
`mill mill.scalalib.scalafmt/` run over the whole tree, never mixed with logic.
**Not changed by this lane.**

## 6. Not adopted

| Candidate | Decision | Reason |
| --- | --- | --- |
| Ox | **not a dependency** | Public API is `Future`-based (PLAN.md §3.2). Overrides SCALA_CODE_STYLE.md's Ox chapter for this repo. |
| cats-effect / ZIO | rejected | PLAN.md ADR-2 — hand-rolled `Exec[F]` keeps the published dependency footprint at four artifacts. |
| circe / jsoniter-scala | rejected | PLAN.md ADR-3 — jsoniter-scala, first-class sttp integration, tiny footprint. |
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
