# ADR-0003 — upickle for JSON

- Status: accepted
- Date: 2026-08-01

## Context

`SCALA_CODE_STYLE.md` §"JSON Codecs" uses jsoniter-scala in its examples.
`PLAN.md` §3.3 specifies upickle. Both are defensible; the choice had to be made
once and recorded, because it is visible in `modules/codec` throughout.

## Decision

Use **upickle** (`com.lihaoyi::upickle`), with explicit `ReadWriter` instances
written next to each wire DTO in `modules/codec`.

## Rationale

- sttp client4 ships a first-party `upickle` integration module, so the response
  path is one dependency rather than two plus glue.
- Small transitive footprint, which matters for a published library — the same
  reasoning as ADR-0002.
- Explicit `ReadWriter`s are something this project wants anyway: golden-fixture
  discipline (ADR-0001) means we are hand-checking every field against captured
  JSON, so derivation convenience is worth less here than usual.

The style guide's jsoniter examples are treated as illustrative of *structure*
(codecs live next to the DTO, in the wire package, derived once) rather than as
a binding library choice. That structure is preserved exactly.

## Consequences

Good: fewer dependencies; one obvious JSON path; sttp integration is supported
rather than hand-rolled.

Bad: upickle has fewer derivation knobs than circe. Two concrete costs:

1. Snake-case wire fields (`created_at`, `html_url`) need explicit mapping.
   Handled with upickle's `@upickle.implicits.key` annotations on the DTO.
2. Top-level JSON arrays need a codec for the collection type as well as the
   element type. `SCALA_CODE_STYLE.md` calls this out; forgetting it compiles
   and fails at runtime. Every list endpoint therefore gets a golden-fixture
   decode test, which catches it.

Decoding failures must never escape as `upickle.core.Abort`. `modules/codec`
converts them to `CodebergError.DecodingFailed` carrying a bounded snippet and a
JSON path, per `PLAN.md` §4.
