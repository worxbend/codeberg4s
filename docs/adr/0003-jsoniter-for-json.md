# ADR-0003 — jsoniter-scala for JSON

- Status: accepted
- Date: 2026-08-02
- Supersedes: the original ADR-0003, which chose jsoniter-scala

## Context

`SCALA_CODE_STYLE.md` §"JSON Codecs" specifies jsoniter-scala. `PLAN.md` §3.3
specified jsoniter-scala. The first version of this ADR chose jsoniter-scala, on two grounds:
that sttp client4 ships a first-party jsoniter-scala integration, and that jsoniter-scala's
transitive footprint is small.

Both grounds turned out to be weaker than they looked.

The sttp integration was never used. The transport reads every response body as
a `String` and hands it to `modules/codec`, precisely so that the JSON library
stays out of the transport — so the integration module was a dependency the
build declared and no code imported. It has been removed.

The footprint argument was a wash: jsoniter-scala's core is comparable, and its
macros module is compile-time only.

Meanwhile the style guide — which `CLAUDE.md` names the single source of truth
for the HTTP/JSON boundary — said jsoniter all along.

## Decision

Use **jsoniter-scala** (`com.github.plokhotnyuk.jsoniter-scala`), 2.39.1.

## How it is used, and why not the obvious way

jsoniter derives a codec per target type. That is the right design for a schema
you control, and the wrong one for this API. `docs/HAZARDS.md` §1 measures that
**no** response definition in the pinned spec declares `required`, that
`nullable` never appears, and that live payloads send JSON `null` for fields the
spec types as arrays and objects. A derived codec answers all three by failing.

So the boundary is two steps rather than one:

1. jsoniter parses the body into `JsonValue`, a small document model with a
   hand-written `JsonValueCodec`;
2. the DTO assembles itself from that document through `JsonFields`, whose
   accessors treat absent, `null` and wrong-kind as one and the same.

This is the same shape the codebase already had — the ~200 DTO readers were
written against `JsonFields`, not against the JSON library — which is why
swapping the engine changed the two files underneath and left the DTOs alone.

## Consequences

Good:

- The style guide and the code agree again.
- One fewer dependency: the unused sttp-jsoniter-scala integration is gone.
- **Numbers are exact.** The previous document model parsed every JSON number as
  a `Double`, which silently loses precision above 2^53. `JsonValue.Num` holds a
  `BigDecimal`, and a test round-trips 2^53 + 1 to prove it.
- **No hex dump in a failure message.** jsoniter appends one to parse errors by
  default; that is response payload, and this library's failures are logged, so
  it is switched off and a test asserts the body does not leak into the message.
- **Depth is bounded.** The document reader is recursive, so a deeply nested body
  is remote input that could exhaust a caller's stack. `JsonValue.MaxDepth`
  rejects it, with a test.
- Decoders are stricter where jsoniter-scala was lenient: `JsonDecoder[String]` requires
  a JSON string, where jsoniter-scala coerced `{"name": 7}` into `"7"`. Nothing wanted
  that coercion, and a silent one at the boundary is how a wrong field reaches
  the domain looking right.

Bad:

- The document model and its codec are ours to maintain: about 190 lines,
  covered by `JsonSuite` and the codec property suites.
- Per-field JSON paths on a *parse* failure are gone. jsoniter-scala's tracing visitor
  could say `$.owner.login`; jsoniter reports an offset. In practice this costs
  nothing: parse failures are now always document-level (the model is total), and
  the field-level paths callers actually see come from each DTO's `toDomain`,
  which knows which field it wanted. Those are unchanged and still asserted.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| Keep jsoniter-scala | The style guide says jsoniter, the sttp integration that justified it was unused, and the `Double` numeric model was a latent precision defect. |
| jsoniter with derived codecs per DTO | Cannot express "every field optional, `null` and absent identical, unknown kinds tolerated" without a per-field knob; `docs/HAZARDS.md` §1 shows the API requires exactly that. |
| circe | A larger dependency, and its optics would not change the shape of the problem above. |
