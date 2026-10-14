# ADR-0007 — Explicit transport ownership and diagnostic boundaries

- Status: accepted
- Date: 2026-10-14

## Context

The Future facade and small module graph already have concrete consumers:
`domain` contains values and contracts, `core` owns the shared execution,
retry and request pipeline, `codec` and `transport` adapt external formats and
I/O, and `client` supplies convenient Future APIs on both failure rails.
Replacing those seams with an effect system or a general framework would not
resolve the defects found in the architecture review.

The pinned sttp JDK backend applies its response-byte option before
content decompression and performs blocking body reads on its execution
context. Its redirect wrapper can replay a mutation body even when it removes
Authorization on a cross-origin redirect. Scheduler queue membership likewise
is not ownership of an outstanding logical deadline: the JDK can internally
cancel a task that no longer appears in the shutdown drain.

Finally, a redacted URI is not a diagnostic policy. Server fields, request ids,
decoder paths/reasons and backend exception messages can echo credentials.
These are concrete boundary failures, not reasons to duplicate execution
pipelines or expand the public dependency surface.

## Decision

Keep the existing module graph, `Exec[F]` ports and Future public API. Strengthen
focused internal seams and add only APIs with actual consumers:

- Own response-stream closure in the bounded reader; mapped input-stream
  response handling avoids a second callback finalizer replacing a primary
  refusal/read failure. Propagate fatal/interrupt cleanup causes, including boxes.
  Read response streams incrementally after decompression, under separate text
  and binary decoded-byte limits for every status. Close on reader exit,
  including exceptional and eventual late completion. The public result remains
  materialized; there is no new streaming API.
- Disable automatic redirect following for every method. Surface 3xx responses
  through the API-error rail; do not create a speculative destination trust
  policy. The organization-member explicit 303 check calls the original route.
- Give the library-owned backend its own body-I/O executor. Caller execution
  context ownership stays with the caller. An injected backend is neither
  reconfigured nor closed by the client; its executor, connect timeout and
  input-stream response capability remain its responsibility.
- Treat the exchange deadline as required, fail closed if the timer cannot
  provide it, and settle logical wrappers on timer shutdown. Track timer
  promises independently of the scheduler queue. Publish completions outside
  bookkeeping locks to allow synchronous callback reentry.
- Guard both synchronous and asynchronous backend failure adaptation. Inspect
  nested causes without cycling, prefer specific transport diagnoses over
  generic I/O, and preserve fatal failures rather than turning them into
  retryable library errors. A small core snapshot seam inspects causes and
  suppressed cleanup once per node, under one identity-based budget. Transport
  classification still uses only the primary chain; telemetry and materialization
  do not swallow unsafe suppressed state or an unseen tail.
- Centralize configured-credential sanitization at the diagnostic boundary,
  before clipping and telemetry. Header-specific password representations also
  account for adapter whitespace normalization without broadening generic text
  matching. Bound telemetry cause traversal as well as transport traversal.
  Sensitive operations withhold unknown
  payload-derived failure material across all call kinds. Successful secret
  values are unchanged. This policy does not discover arbitrary transformed or
  unrelated secrets in ordinary server text.
- Preserve exact wire filenames with an additive exact `ContentPath` parser;
  retain the trimming convenience parser and literal contracts. Reject bad
  nested-array members at actual DTO-reader boundaries without altering public
  lenient projection signatures. Separate authored Git dates from service
  timestamp sentinels. Bound collision probing with a shared ordered fallback,
  preserving first-field and duplicate semantics. Retain descriptive all-space
  filenames and contextual paths through generic envelope composition.
- Compute retry waits in capped nanoseconds. Preserve integral-millisecond jitter
  grids and use finer ticks only for fractional computed delays; sample the
  inclusive maximum without arithmetic overflow or distribution bias.

## Consequences

No dependencies, endpoint scope, compiler/JDK pins or public effect model
change. Exception and typed-Either rails continue to share one pipeline.
There is no unnecessary inheritance or universal "request framework".

Behavior deliberately tightens at the security boundaries: redirects are not
followed, compressed over-limit responses fail, malformed nested members no
longer vanish, and sensitive failure diagnostics disclose less. Injected
backend implementations must support input-stream response handling. Exact
filename and date preservation correct identity/data loss without changing
the legacy user-input convenience constructors.

A logical timeout is not physical Future cancellation. Queueing, telemetry,
decode work and retry backoff are not a total-call elapsed-time budget.
`released` observes owned close completion, not the termination of every
outstanding caller-owned request. Server-side mutation outcome remains uncertain
when a response is lost. These limits are documented rather than hidden behind
an abstraction that cannot guarantee them.

Verification uses deterministic fake ports for policy, real loopback HTTP for
transport behavior, disposable Forgejo for supported endpoint integration, and
isolated Scala consumers for public source contracts. These evidence classes
must not be described as authenticated live Codeberg coverage.
