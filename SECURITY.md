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

If the repository's host offers private vulnerability reporting (GitHub's
"Report a vulnerability" button, for instance), that works too and reaches the
same person.

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

The library's central promise is that a token cannot escape through a
diagnostic path. `ApiToken` renders as `***` in `toString` and in string
interpolation, `reveal` is the only accessor, `CallContext` holds a URI that
was redacted before the context was built, and `CodebergException`'s message is
`CodebergError.describe`, which is assembled only from that redacted context
and from server-supplied text. There are tests asserting each of these, and
property suites over the rendering paths.

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
  configured `BaseUri`, including redirect handling.
- Anything that weakens TLS verification.
- A retry of a non-idempotent operation. `POST` and `PATCH` that create or edit
  are deliberately never retried; a change that makes one retryable can file
  the same issue twice or double a payment-like side effect.

### Parsing and resource use

- A crafted 2xx payload that causes unbounded memory growth, non-terminating
  decoding, or an exception that escapes the `CodebergError` channel.
- A `Link` header that drives a pagination walk into an infinite loop. Note the
  documented guard: a walk stops when the server stops offering a next page,
  and callers are told to guard on an empty page as well.

### Dependencies

A known vulnerability in sttp client4 or upickle that this library exposes.
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
  `client.downloads` — hold the whole archive in memory; the library does not
  stream, and attachment streaming above 50 MB is explicitly out of scope for
  v1. A large artifact exhausting the heap is documented behaviour. If you can
  make it happen with a *small* request, that is a report.
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
