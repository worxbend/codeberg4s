# Security policy

codeberg4s is a client library. It holds an API token in memory, puts it in an
`Authorization` header, and builds request paths out of values a caller
supplies. Almost everything that could go wrong here falls into one of those
three sentences.

## Reporting a vulnerability

**Email <balyszyn@gmail.com>. Do not open a public issue.**

Include, as far as you can:

- what an attacker gains, and what they need already to get it;
- the affected version or commit;
- the operation id or method involved, if there is one;
- a minimal reproduction — Scala that compiles is ideal;
- **a redacted transcript.** If your evidence contains a real token, replace it
  with a placeholder and say so. Do not send working credentials; a report is
  not worth creating a second incident.

GitHub's private vulnerability reporting — the "Report a vulnerability" button
on [github.com/worxbend/codeberg4s](https://github.com/worxbend/codeberg4s/security)
— works too and reaches the same person.

There is no bug bounty and no published PGP key. If you need an encrypted
channel, say so in a first message containing nothing sensitive and one will be
arranged.

### What to expect

This project is maintained by one person, so these are commitments that can
actually be kept rather than aspirational ones:

| Stage | Target |
| --- | --- |
| Acknowledgement that the report arrived | 7 days |
| An assessment — is it a vulnerability, and how severe | 14 days |
| A fix released, for anything credible and high severity | 30 days |
| Public disclosure | after a fix ships, or 90 days, whichever is first |

If you have not heard anything after 14 days, assume the mail went astray and
send it again. Credit is given in the changelog unless you ask otherwise.

## Supported versions

| Version | Supported |
| --- | --- |
| `0.1.0-SNAPSHOT` (unreleased) | this is the development line; fixes land on `main` |

**Nothing has been released yet.** Once `0.1.0` ships, the policy is: fixes go
to the latest patch of the current minor, and — while the major version is `0`,
under the Early SemVer scheme this project declares — the previous minor gets a
backport only if the fix is small and the release is recent. Pinning an old
`0.x` is not a supported way to avoid an upgrade.

Because the library is published as five artifacts under one version, a
security fix is released across all five together even when only one changed.

## What is in scope

Anything reachable through the published API of the five artifacts.

### Credential leakage — the sharpest class of bug this library can have

`ApiToken` renders as `***`; `reveal` is its deliberate material accessor.
The client's diagnostic boundary scrubs the configured token, password and
actual UTF-8 Basic wire credential from contexts, server fields, decoder
failures and transport details before clipping or telemetry. Sensitive
credential reading, issuing and writing operations withhold unknown
payload-derived failure details without changing successful results.

This is not detection of unrelated secrets or arbitrary transformations in
ordinary server text. Independently constructed errors are outside that
client-bound policy. Keep application logging policy; report bypasses of the
explicit protections privately. Regression suites inspect whole errors and
callback arguments, not merely their formatted descriptions.

**Any way to get credential material out through one of these is a
vulnerability, not a bug**, however unlikely the path:

- a token, password or basic-auth header appearing in a `toString`, a
  `describe`, an exception message, a stack trace or a telemetry callback;
- a credential surviving in a URI, a query parameter or a header that reaches
  `CallContext`, a log line or an error body;
- a redaction that can be defeated by a crafted server response — an error body
  echoed back into a message, for instance;
- a credential written to disk, to a temporary file, or into a coverage or
  debugging artifact.

Report these privately even if the path looks theoretical.

### Request forgery through identifiers

Owners, repository names, branch names, labels, paths and page sizes are opaque
types with `Either`-returning smart constructors, and those constructors are a
security boundary, not a convenience. A value that escapes validation and
changes the request path — path traversal out of `/repos/{owner}/{repo}/…`,
a smuggled query parameter, a CRLF injected into a header — is in scope.

### Transport and configuration

- Anything that would cause a request to be sent to a host other than the
  configured `BaseUri`, including redirect handling. Automatic HTTP redirects
  are disabled for every method: 3xx responses remain API errors, not body or
  credential replay to a `Location` destination.
- Anything that weakens TLS verification.
- A retry of a non-idempotent operation. `POST` and `PATCH` that create or edit
  are deliberately never retried; a change that makes one retryable can file
  the same issue twice or double a payment-like side effect.

### Parsing and resource use

- A crafted 2xx payload that causes unbounded memory growth, non-terminating
  decoding, or an exception that escapes the `CodebergError` channel. Note the
  documented guard: every response is read under a byte bound —
  `CodebergConfig.maxResponseBodyBytes`, 16 MiB by default, and
  `maxDownloadBodyBytes`, 50 MiB, for the two archive downloads — and a body
  that passes it is rejected as `TransportCause.ResponseTooLarge` rather than
  materialized to the end. Limits count decompressed bytes for every HTTP
  status; the bounded stream reader closes on exit. Logical Future timeout is
  not a guarantee of immediate physical stream cancellation. A payload that
  exhausts memory while staying *inside* that
  bound is a report.
- A `Link` header that defeats bounded pagination. `PageWalk.collect` and
  `fold` follow advertised metadata and fail if another page remains at their
  validated `maxPages` bound; `exists` may return earlier on a match. Manual
  walkers must impose their own bound. Empty linked pages alone are not a
  termination promise.

### Dependencies

A known vulnerability in sttp client4 or jsoniter-scala that this library exposes.
The dependency surface is deliberately two libraries; `codeberg4s-domain` has
none at all.

## What is out of scope

- **Vulnerabilities in Forgejo, Gitea or codeberg.org.** Report those upstream.
  Their trackers are the right place; this library only calls them.
- **Anything requiring the attacker to already run code in your process.** A
  heap dump contains the token; that is what holding a credential in memory
  means, and no library-level mitigation changes it.
- **Configuration you chose.** Pointing `BaseUri` at a host you do not trust,
  logging `token.reveal` yourself, or committing a token to a repository.
- **Rate limiting and denial of service against a remote instance** caused by
  your own call volume. The retry policy is bounded and honours `Retry-After`;
  driving a walk over ten thousand pages is a decision the caller makes.
- **Known limitations already documented.** Two operations —
  `DownloadActionArtifact` and `repoGetActionRunLogs`, reachable as
  `client.repos.actions.downloadArtifact` and `downloadRunLogs` — hold the whole archive in memory; the library does not
  stream, and attachment streaming above 50 MB is explicitly out of scope for
  v1. Those two are bounded by `CodebergConfig.maxDownloadBodyBytes`, which
  defaults to 50 MiB for exactly that reason; raising the setting yourself and
  then running out of heap is your configuration, not a vulnerability.
- **Missing hardening with no exploit path**, such as the absence of
  certificate pinning.

## What this project does to reduce the risk

Stated so you know what to test against, not as a claim of safety:

- `-Werror` with `-Wunused:all`, `-Wvalue-discard` and `-Wnonunit-statement`;
  Scalafix bans `null`, `throw`, `return`, `var`, casts and unsafe `Option`
  access outright.
- No recoverable failure is thrown: failures are `CodebergError`, and a bare
  `new Exception` in production code fails `./verify.sh`.
- Redaction is tested directly, including property suites asserting that no
  rendering path emits a credential.
- Every request carries a response-body byte bound, so an instance cannot
  answer a call with as many bytes as it likes. Exceeding it is not retried:
  repeating the call would download the oversized body once per attempt.
- Two dependencies, both widely used, both pinned in `build.mill`.
- Released artifacts are PGP-signed and built by
  [`.github/workflows/release.yml`](.github/workflows/release.yml) from a
  tagged commit, after the same `./verify.sh` every push runs. Signing keys and
  Sonatype tokens live only in repository secrets, and are passed to Mill as
  environment variables so they never reach a process listing or a build log.
  See [`RELEASING.md`](RELEASING.md).

## If a released artifact is compromised

Maven Central is immutable: a published version cannot be altered or withdrawn.
The response is to release a fixed version immediately, mark the bad one in
`CHANGELOG.md`, and — if a credential was exposed — rotate it first, because
rotation is the only mitigation that does not depend on anyone upgrading.
[`RELEASING.md`](RELEASING.md) § "When a release is bad" has the procedure.
