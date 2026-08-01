# ADR-0005 — `Future`-based public API, resolving the PLAN / STYLE conflict

- Status: accepted
- Date: 2026-08-01

## Context

The repository carries two documents that disagree about the shape of the public
API, and `CLAUDE.md` names one of them the single source of truth:

| Question             | `PLAN.md`                                 | `SCALA_CODE_STYLE.md`                          |
| -------------------- | ----------------------------------------- | ---------------------------------------------- |
| Concurrency          | `Future`, sttp `Backend[Future]`          | Direct style, Ox `supervised` scopes           |
| Error channel        | `Future` failing with `CodebergException` | `Either[CodebergError, A]`                     |
| Pagination           | `Page[A]`, `listAll: Future[Vector[A]]`   | `ox.flow.Flow[A]`                              |
| JSON                 | upickle                                   | jsoniter-scala                                 |
| Package prefix       | `codeberg4s.*`                            | `com.worxbend.codeberg4s.*`                    |

This is not a style disagreement that can be split; the two produce different
libraries.

## Decision

**`PLAN.md` wins on API shape. `SCALA_CODE_STYLE.md` wins on everything else.**
This was confirmed with the repository owner before any production code was
written.

Concretely:

- The public API is `Future`-based. There is **no Ox dependency**.
- JSON is upickle (ADR-0003).
- Pagination is `Page[A]` plus `listAll` / `foldPages` (`PLAN.md` §3.2), not
  `Flow`.
- The package prefix is `com.worxbend.codeberg4s` — the style guide wins here,
  because package layout is squarely inside the area `CLAUDE.md` delegates to it,
  and `PLAN.md`'s `codeberg4s.*` was an illustrative sketch rather than a
  decision.
- Every other rule in `SCALA_CODE_STYLE.md` applies unchanged: indentation
  syntax, explicit result types, opaque types with smart constructors, no
  `null` / `var` / `return` / bare `throw`, secrets redacted, warnings as
  errors, one behaviour per test, Scaladoc on the public API.

## The dual rail

`PLAN.md` §3.2 asks for two error contracts, and both are kept, because they
serve genuinely different callers:

```scala
client.repos.get(owner, name):         Future[Repository]                        // fails with CodebergException
client.repos.attempt.get(owner, name): Future[Either[CodebergError, Repository]] // never fails
```

`CodebergException` carries the full `CodebergError` ADT, so the convenience rail
loses no information — `recover { case e: CodebergException => e.error }`
recovers the typed value.

The rails are mechanical projections of one implementation in `modules/core`.
Duplicating logic across them is a review-blocking defect.

## Consequences

Good: idiomatic for the `Future`-using majority; no effect-system dependency; the
style guide still governs the parts that make the code readable and safe.

Bad: `Future` is eager, which makes retry and pagination subtle — a `Future`
already running cannot be re-run. Mitigated exactly as `PLAN.md` §9 proposes:
all such logic lives in `core` over `Exec[F]` and is tested with `F = Either`;
`Future` appears only at the outermost projection, where each attempt is a fresh
thunk (`Exec.suspend`).

Bad: the style guide's Ox and jsoniter examples no longer match the code.
`SCALA_CODE_STYLE.md` is left unedited — it is upstream-derived — and this ADR is
the pointer that explains the divergence.
