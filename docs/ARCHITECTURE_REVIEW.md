# Architecture and library-quality review

Review baseline: `c9ce48feb6a7dbabf5733bee4a2735b4ee4ed106`, initially clean `main`.
Status: implemented and verified. Final repository-wide, property, integration, documentation, packaging and external-consumer checks passed; focused independent acceptance has no remaining concrete blocker.

## Scope and design judgment

Read CONTRIBUTING, SCALA_CODE_STYLE, build.mill, both CI implementations,
verify.sh, PLAN.md/current status, architecture ADRs, security/hazard/release
policy and the pinned Forgejo specification. Specification SHA-256 remains
`253d09802be4a97244170c8c68afce22d1ce2e2a7ff6fc3ede2cd0ec4b9eafa0`.

The five-module graph is useful rather than accidental:

- domain: validated concepts, commands, values and public failure contracts;
- core: shared execution, retry, pagination and request/diagnostic policy;
- codec: concrete wire adaptation and checked structural boundaries;
- transport: concrete HTTP, decoded-byte and native resource ownership;
- client: the Future facade and additive high-level traversal conveniences.

Keep the Future API and both rails. There is no new effect system, dependency,
endpoint expansion or inheritance framework. Ports/adapters, small factories
and the existing facade provide useful OOP boundaries; no additional GoF
hierarchy is needed. Narrow shared seams replace demonstrated repetition:
configured diagnostic sanitization, bounded foreign failure snapshots, JSON
field indexing, checked reader construction and nested-member traversal.

Preserve public infallible DTO/projection signatures while validating at actual
reader boundaries. Preserve the legacy trimming path constructor and add
`ContentPath.fromExact` for exact identities with real wire/follow-up consumers.
See ADR-0007 for ownership and compatibility decisions.

## Ranked findings, evidence and remedies

References in this section identify the baseline/review snapshot where the
finding was established; edited-file line numbers can subsequently move.
All runtime reproductions use synthetic credentials/errors and local peers.

| Priority / reference | Problem → trigger → consequence → remediation / evidence |
| --- | --- |
| P1 `modules/transport/src/com/worxbend/codeberg4s/transport/SttpHttpPort.scala:250-253` | Native sttp bounds compressed bytes before decompression → small compressed response expands past limit → allocation bound bypass → incremental post-decompression reader, at most bound + one probe byte. Real loopback gzip/deflate, identity, text/binary, 2xx/non-2xx and fixed/chunked tests reproduced the defect. |
| P1 `modules/transport/src/com/worxbend/codeberg4s/transport/SttpHttpPort.scala:147-150,355-356` | Default redirect wrapper can replay a body → secret-bearing mutation redirected → body disclosure/replay despite stripped Authorization → disable redirects in request and native layers; 3xx remains API error. Destination-counter HTTP tests reproduce and protect this. |
| P1 `modules/core/src/com/worxbend/codeberg4s/core/ApiPipeline.scala:197-204,237-240`; transport detail | Server/backend/decoder echoes are assumed safe → credential in message, field, request-id or clipping boundary → errors, exceptions and telemetry disclose it → scrub configured token/password/UTF-8 Basic wire material before clipping/observation; sensitive operations withhold unknown payload-derived diagnostics across call kinds. Both-rail and whole-callback regressions preserve successful secrets/ordinary diagnostics. |
| P1 `modules/transport/src/com/worxbend/codeberg4s/transport/SttpHttpPort.scala:200-214`; `modules/client/src/com/worxbend/codeberg4s/CodebergClient.scala:142-145` | Failed deadlines are discarded → close or timer refusal while body stalls → indefinitely pending logical Future → required deadline before admission; failure settles the logical wrapper as the original lifecycle failure, without retry or physical cancellation claims. Public and low-level regressions reproduce both rails. |
| P1 `modules/transport/src/com/worxbend/codeberg4s/transport/SttpHttpPort.scala:209-214,328-356` | Blocking body read shares caller executor → one-thread caller pool and stalled body → timeout/continuations starve → owned separate native/body I/O resources and independent small completion callbacks. Both public rails reproduced against archived baseline jars; real HTTP acceptance releases peer only after logical timeout. |
| P2 `modules/client/src/com/worxbend/codeberg4s/client/FutureTimer.scala:60-78,142-146` | Queue membership is treated as outstanding promise ownership → accepted/dequeued task self-cancels or close races admission → lost promise → independent pending registry; scheduler calls/publications outside monitor; late handles canceled. Controlled scheduler barriers reproduce admission/dequeue races and callback reentry. |
| P2 `modules/transport/src/com/worxbend/codeberg4s/transport/SttpHttpPort.scala:181-191,511-538` | Synchronous send throws bypass adapter; generic I/O masks deeper causes; boxed fatal is ordinary Unknown → rail/retry drift → one sync/async boundary, specific primary-cause precedence, bounded identity inspection and fatal preservation. Tests cover causes, cycles, budgets, failure identity and retry classification. |
| P1/P2 `modules/domain/src/com/worxbend/codeberg4s/PathSegment.scala:55-57`; contents/tree and adjacent filename DTOs | Wire paths trim; all-space descriptive names blank-fold → legitimate padded/all-space filename → follow-up retargeting or lost metadata → exact runtime ContentPath/raw filename extraction; descriptive strings remain raw nonempty, not forced into ContentPath. Legacy constructor/literal and empty-value guards remain. Isolated consumer verifies outgoing encoded path plus decoded identity. |
| P2 `modules/codec/src/com/worxbend/codeberg4s/codec/JsonFields.scala:135-141` and actual DTO readers | Nested wrong-kind members silently compact → mixed malformed arrays → incomplete results / shifted failure indexes → checked DTO-reader traversal before existing public projections; generic envelope context composition and primary contents-member indexes retained. Wrong-kind optional containers/scalars remain deliberately lenient. |
| P2 `modules/codec/src/com/worxbend/codeberg4s/codec/Timestamps.scala:38-42` | Global service sentinel filtering → real epoch/pre-epoch Git date → valid metadata disappears → split authored Git policy from unchanged service sentinels; precise Go-zero remains absent. Standalone and embedded date regressions cover offsets/nanoseconds. |
| P2 `modules/codec/src/com/worxbend/codeberg4s/codec/JsonValue.scala:401-424`; `modules/codec/src/com/worxbend/codeberg4s/codec/JsonFields.scala:180-198` | Open-addressed collision clusters → hostile key family → quadratic duplicate detection/build/lookup → shared 32-probe cap and ordered fallback preserving first key/null/document-order semantics. Deterministic budget tests plus JVM before/after measurements, no timing gate. |
| P2 `modules/core/src/com/worxbend/codeberg4s/core/RetryEngine.scala:140-146`; `JitterSource.Random` | Millisecond conversion/inclusive-bound addition → validated fractional waits or Long.MaxValue bound → truncation/overflow → capped nanosecond arithmetic; integral computed jitter retains millisecond grid, fractional uses nanos; unbiased safe inclusive-max sampling. Fake timer and archived direct-source RED checks protect compatibility. |
| P1 follow-up `modules/core/src/com/worxbend/codeberg4s/core/CodebergResponse.scala:35-40,60-62` | Header trimming precedes exact redaction → significant-whitespace Basic password in correlation id → credential fragment survives → private raw-header diagnostic path and header-only normalized representations, preserving public getters/generic matching. Success/failure, callbacks and both rails reproduced. |
| P2 follow-up `modules/client/src/com/worxbend/codeberg4s/client/GuardedTelemetry.scala:82-85` | Cause-only recursive ExecutionException unboxing → cycle/deep chain or fatal behind another wrapper → pending call or hidden fatal → bounded shared cause/suppressed snapshot on sync/async callback boundaries. Ordinary failures/cycles remain swallowed; unsafe/unknown tails remain failed effects. |
| P2 follow-up `modules/transport/src/com/worxbend/codeberg4s/transport/BoundedResponseBody.scala:46`; pinned native finalizers | Close replaces primary refusal/read failure; native abnormal second close suppresses unsafe cleanup → size refusal or read/acquisition I/O + failing close → retryable misclassification / hidden fatal or interrupt → reader-owned mapped stream closure plus bounded suppressed-state inspection before adaptation. Real pinned backend first/repeat/always close and acquisition tests reproduce; primary physical failure identity preserved. |
| P2 follow-up `modules/client/src/com/worxbend/codeberg4s/client/FutureExec.scala:55` | Wrapper type alone decides materialization → public CodebergException carrying fatal cause/suppression → unsafe state becomes ordinary Left and can be ignored → materialize only a known-safe inspected library failure. Deterministic queued-executor RED checks reproduced both cause/suppressed variants. |
| P2 `RELEASING.md:81-85`; executable site error examples | Private constructors mistaken for stable case-class shape; stale three-field Api extractor → external positional consumer / mdoc → source compilation fails → correct growth/JVM compatibility policy and four-field examples. Original four-field consumer compiles; synthetic appended-field consumer fails with actual wrong-arity diagnostic. Site compiles without gate weakening. |

## Baseline and test-driven evidence

Baseline `./verify.sh --with-slow`: 3,809 tests passed, all five coverage
floors, duplication 151 at 40 tokens and worst CRAP 28.0/30. Initial execution
was blocked at coverage by missing scala-cli; CI-pinned scala-cli 1.15.0 and
Coursier 2.1.24 were installed in scratch with both archive checksums verified.
The ready baseline passed unchanged.

Baseline disposable Forgejo: 7/7 tests; image
`codeberg.org/forgejo/forgejo@sha256:dbb0f88677f0c65cd1b66fb83504225aa5a04c4bc4a5ffdf9fc9a3a6d5bb1c68`.
Baseline packaging and isolated consumer also passed.

Observed RED sequences (not compiler errors):

- Initial changed-module run: 4,539 executed / 469 failed. An invalid collision
  assertion expecting `Some(Null)` was corrected to preserve intentional
  null-as-absence behavior; it was not treated as a production defect.
- Archived public owned-caller probe: 2/2 timed out before peer release.
- Independent-review follow-up run, excluding unchanged domain: 3,722 executed /
  240 failed. Owned physical lifetime acceptance already passed all three cases.
- Direct JitterSource against archived baseline: 1/4 failed at inclusive maximum.
- Boxed-close follow-up: 2/21 failed; native suppressed unsafe cleanup: 4/32 failed;
  sibling telemetry suppression: 2/154 failed; unsafe materialization: 2/11 failed.

A raw-fatal MUnit `intercept` fixture escaped the test runner and skipped later
cases. It was corrected to an explicit synthetic LinkageError catch with the
same identity assertion; production fatal handling was not weakened. Final
verification must execute the full matrix without those skips.

## Independent review and gate corrections

Investigation, regression authoring, implementation and independent review used
separate disjoint agents. Parent alone executed shared builds, tests and probes.
Independent plan review required raw filename extraction, native timeout
rounding, wider diagnostic fields, public projection compatibility and explicit
stream/executor ownership. Post-implementation review added the header,
telemetry, adjacent filename/composition and cleanup-precedence fixes above.
Focused contract/retry acceptance approved the behavior and proportional design.
Suppressed-state and duplication-remediation acceptance both approved the final source. Parent execution below also closes the reviewers' explicitly deferred client-suite GREEN checks.

No formatting/lint/coverage/duplication threshold, fixture snapshot, build pin,
workflow or specification was weakened. New sources were explicitly formatted
without staging because git-aware formatter selection omits untracked files.
Scalafix organized imports and enforced the existing no-vars/equality rules.
A line-local foreign-throwable rethrow exemption preserves original throwable
identity; global lint rules are unchanged.

A full gate found duplication 157 against 151. Remediation consolidates
checked-reader construction and validation-only member traversal/ordered field
checks, removes redundant validators, and reuses actual consumer behavior.
No alias trick, token threshold change or increased baseline is used.

## Performance evidence

These are reproducible JVM probes, not JMH or a production latency guarantee.
Same synthetic 4,096-key collision family / five rounds / twenty operations:

| Operation | Baseline median | Frozen-source median | Ratio |
| --- | ---: | ---: | ---: |
| unique parse | 39.111 ms | 2.469 ms | 15.84x faster |
| duplicate parse | 43.439 ms | 2.462 ms | 17.64x faster |
| all-field lookup | 58.164 ms | 0.705 ms | 82.54x faster |

Ordered fallback trades temporary construction allocation for bounded work:
unique parse allocation rose from 671,920 to 2,560,920 B/op on this adversarial
family. Frozen-source normal golden 50-item byte decode rose from 1,023,360 to
1,039,000 B/op (+1.53%); wide repository decode from 23,880 to 24,136 B/op
(+1.07%). Narrow organization decode rose from 3,240 to 3,336 B/op; this is
additional boundary/instrumentation work, not a claim of zero normal-path cost. Timing
spread is substantial; no ordinary-payload speedup or regression is asserted
from these exploratory timing samples. The table uses the completed frozen-source rerun; initial post-fix measurements are retained in scratch separately.

## Evidence classes and remaining limits

- Mock/fake-port policy tests are not network enforcement evidence.
- Loopback HTTP and instrumented actual pinned JDK backend prove transport,
  decompression, finalizer and owned-executor behavior locally.
- Disposable Forgejo validates supported integration paths, not authenticated
  coverage of every Codeberg endpoint.
- Anonymous live Codeberg GET `/version` and `/settings/api` succeeded. The
  observed version was `16.0.0-dev-785-465b60ef+gitea-1.22.0`; settings were
  maximum response items 50, default paging 30, Git tree limit 1000 and blob
  bytes 10485760. No live credentials or mutations were used.

Deliberately retained limits:

- No total-call/walk elapsed budget; deadlines bound individual logical exchanges,
  not arbitrary telemetry, decoding, queueing or backoff.
- Future timeout/close is not physical cancellation or proof of remote mutation
  rollback. Release acknowledges close; abandoned physical reads can retain
  daemon I/O until settlement. No aggregate concurrency or exact heap budget.
- Injected backend ownership, scheduling/connect configuration and input-stream
  response support remain caller obligations. Native/TLS verification is retained.
- Automatic redirects are intentionally rejected, including downloads; deployments
  relying on storage redirects need a separately authorized application path.
- Configured credential protection and sensitive-operation withholding are not
  arbitrary transformed-secret detection. Raw fatal/defect effects preserve their
  throwable identity; application logging/escaping policy still matters.
- HTTP-date Retry-After, fractional numeric-to-integer wire leniency, optional
  wrong-kind containers/scalars and legacy trimming literals remain intentional.
- Bounded page traversal follows advertised metadata and fails on remaining pages
  at its cap; cyclic links cannot silently produce complete success. Concurrent
  writer isolation is not provided by replay-safe replacement semantics.
- No released MIMA baseline or real mutation-testing score exists; neither is
  fabricated or claimed as passed. Extractor growth remains a deliberate source
  shape break despite private Scala constructors/public JVM forwarders.

## Compatibility and final verification

Source signatures/products, Future/exception/Either rails, artifact graph,
Scala 3.8.4/JDK 25, endpoint scope and dependency footprint are preserved.
Behavior deliberately tightens at redirect, diagnostic, byte-limit and malformed
member boundaries. Exact paths/dates correct data loss. Fractional jitter uses
finer ticks; integral computed policy/source behavior is unchanged.

### Final execution results

| Check / actual command | Result |
| --- | --- |
| `./verify.sh --with-slow` | PASS in 31s; 5,178 executed tests, 0 failures, 0 aborted/skipped-case markers. The nine Property suites are deliberately excluded in this unit run and executed separately. Formatting, Scalafix/lint, compilation, examples, rail delegation and module boundaries pass. |
| Coverage | PASS for all five unchanged floors; statement/branch domain 98.92/99.28%, core 95.72/93.23%, codec 95.80/92.86%, transport 92.86/92.22%, client 88.89/100.00%. |
| Slow static gates | CPD 150 groups at unchanged 40-token threshold versus unchanged baseline 151; CRAP worst 28.0/30 across 3,690 measured methods. Public primitive/factory tests cover the formerly under-exercised JsonDecoder anonymous group at 100%; no metric rename, suppression or threshold adjustment. |
| `./verify.sh --properties` | PASS; 71 properties executed across 9 suites, 0 failures. Non-property suites are deliberately filtered out. |
| `./mill --no-daemon -j 1 modules.it.test.testOnly com.worxbend.codeberg4s.it.ForgejoContainerSuite` | PASS; 7 executed, 0 failures. Disposable local Forgejo image/digest as recorded above. |
| `scripts/site.sh` | PASS; 42 mdoc files, 0 compilation errors; Laika HTML and combined API documentation generated. Seven example programs indexed. |
| `./mill --no-daemon -j 1 show 'modules.{domain,core,codec,transport,client}.publishArtifacts'` | PASS; five artifact families / twenty nonempty main, source, javadoc jars and POM files inspected. Main classfiles are major version 69 (JDK 25); POM dependencies preserve the existing graph. |
| Isolated Scala CLI consumer, `--server=false --scala 3.8.4 --jvm 25` with scratch-only Maven repository and real client artifact | PASS; custom public JSON reader/vector composition, literal constructors, exact paths including all-space/invalid-path guards, outgoing percent encoding, both Future rails, decoded filename identity and lifecycle. Maven Central fallback misses are expected for unpublished coordinates; local staged dependencies resolved and the executable exited 0. |
| Frozen-source collision / golden probes | PASS execution; actual allocation/time outputs and tradeoffs above. They are evidence, not timing gates. |
| Final anonymous Codeberg smoke against frozen real artifacts | PASS; both GET `/version` and `/settings/api` returned Right, as above. No authentication, account writes or live endpoint claims beyond these two reads. |
| Final independent source acceptance | Contracts/retry, suppressed-failure/fatal-rail and checked-reader/traversal duplication chunks approved; no remaining concrete blocker recorded. Reviews were read-only; execution is parent evidence, not inferred from reviews. |
| Final repository scope | `git diff --check` clean; HEAD unchanged; no diff to build.mill, verify.sh, CI workflows, formatter/linter configuration or specification. |

The final unit count increased from 3,809 to 5,178 (+1,369), counted from test
runner output in Python rather than registrations/predictions. Diagnostic,
public Future, helper and actual native cleanup matrices all executed after
repairing the fatal fixture; none was accepted on a partially aborted suite.

### Warnings and honest qualification

- Packaging initially hit a transient missing client Scaladoc output-directory
  error. A standalone `modules.client.scalaDocGenerated` retry succeeded; the
  subsequent full artifact build and inspection succeeded. No fake documentation
  artifact or changed build task was substituted.
- Existing per-module Scaladoc unresolved-link/private/downstream-reference
  warnings remain. Combined site generation also reports multiple Scala standard
  library/classpath and non-main-thread Laika IOApp warnings; output generation
  and mdoc execution passed. Zero errors does not mean zero warnings.
- JVM terminal-deprecation/native-access warnings and optional SLF4J binding
  warnings remain tooling/platform output, not test failures. The unrelated
  host conda completion-footer error is not the command's captured exit result.
- Known duplication is still tracked debt, not a claim of clone-free design.
  The recorded gate baseline remains 151; no baseline was raised or quietly
  changed to obtain acceptance.

Raw logs and agent reports are in
`/home/worxbend/.hermes/cache/scratch/codeberg4s-*`; scratch is prunable, so this
report is the durable evidence summary. Generated site entry point:
`out/site/html/index.html`. Real artifact locations are enumerated by the final
publishArtifacts manifest in scratch and their module output directories.
No commits, pushes, tags, live account writes or publication were performed.
