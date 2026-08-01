# ADR-0001 — Curated hand-written models over Swagger codegen

- Status: accepted
- Date: 2026-08-01
- Supersedes: —

## Context

Codeberg publishes a Swagger 2.0 document (`spec/swagger.v1.json`) describing the
Forgejo v1 API. Generating models and clients from it is the obvious first idea,
and several tools can do it: `openapi-generator`, `guardrail`,
`sttp-openapi-codegen`.

Three properties of this particular spec make generation a poor fit:

1. **Swagger 2.0 has no `nullable`.** Optionality is expressed only through the
   `required` list, and the Forgejo spec's `required` lists disagree with what
   the server actually sends. A generator produces `String` where the API sends
   `null`, and `Option[String]` where the field is always present. Both are
   wrong in ways that only show up at runtime, in a user's application.
2. **Union responses.** `GET /repos/{owner}/{repo}/contents/{filepath}` returns
   either a single content object or an array of them depending on whether the
   path is a file or a directory. Swagger 2.0 cannot express that; generators
   emit either the wrong arm or an untyped escape hatch.
3. **Generated names are not API design.** `operationId`-derived names
   (`repoGetContents`, `issueGetCommentsAndTimeline`) leak the spec's internal
   naming into a published Scala API, where they are permanent.

## Decision

Write the domain models, DTOs and codecs by hand, and validate them against
**golden fixtures captured from the live API** rather than against the spec.
The spec is vendored and checksummed as a *reference and drift detector*, not as
a source of code.

Where the spec and a golden fixture disagree, the fixture wins.

## Consequences

Good:

- Optionality reflects observed reality, so `Option` means "the server really
  omits this".
- Union responses become explicit Scala 3 ADTs
  (`RepositoryContent.File | RepositoryContent.Directory`), not `ujson.Value`.
- Names follow `SCALA_CODE_STYLE.md`, not the spec author's conventions.
- Scaladoc can state per-operation error contracts, which no generator emits.

Bad:

- Endpoint coverage is manual work, and the library will lag the API.
  Mitigated by the wave plan in `PLAN.md` §7 and by the drift detector below.
- Two sources of truth (spec + fixtures) must be kept aligned. Mitigated by
  `docs/API_INVENTORY.md`, whose checkboxes are the coverage ledger, and by a
  nightly job that re-fetches the spec and diffs it against the pinned copy.

## Rejected alternatives

| Alternative              | Why rejected                                                                   |
| ------------------------ | ------------------------------------------------------------------------------ |
| `openapi-generator`      | Emits Java-shaped Scala; no Scala 3 ADTs; optionality follows the spec's lies.  |
| `guardrail`              | Better output, but still spec-faithful, and union responses are unsupported.    |
| `sttp-openapi-codegen`   | Ties the model layer to sttp, breaking the hexagonal boundary in `PLAN.md` §3.  |
| Generate, then hand-edit | The edits are lost on every regeneration; the worst of both approaches.         |
