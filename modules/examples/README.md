# modules/examples

Runnable programs that show how to use codeberg4s. They are **compiled by the
build** — under the same `scalacOptions` as the library, `-Werror` included — and
**published nowhere**.

That is the whole reason they live here rather than in Markdown. A snippet in a
README rots silently the day a signature changes; a program in this module
breaks `./mill modules.__.compile`, and therefore `./verify.sh`, on the commit
that invalidated it.

`modules.examples` is deliberately **not** a `Codeberg4sPublishModule`. Shipping
it to Maven Central would put `main` methods on a consumer's classpath, and Mill
would demand that every dependency of a published module be publishable too.

## Running one

```bash
./mill modules.examples.runMain com.worxbend.codeberg4s.examples.HelloCodeberg
```

Each program repeats its own command and its environment variables in its
Scaladoc. Every one that needs a credential degrades gracefully: it explains
what to set and exits normally rather than throwing.

| Program            | Reads                                                        | Writes? | Shows                                                              |
| ------------------ | ------------------------------------------------------------ | ------- | ------------------------------------------------------------------ |
| `HelloCodeberg`    | nothing                                                      | no      | the smallest complete program: config, call, await, close           |
| `Authenticating`   | `CODEBERG_TOKEN` (optional)                                  | no      | `Auth.Anonymous`, `Auth.Token`, `Auth.Basic`, and a rejected token  |
| `HandlingErrors`   | nothing                                                      | no      | both rails on one call, and every `CodebergError` case               |
| `WalkingPages`     | nothing                                                      | no      | pagination driven by `Page.nextPage`, a bounded fold, then the same fold through `PageWalk` from `client.firstPage` |
| `CreatingAnIssue`  | `CODEBERG_TOKEN`, `CODEBERG_OWNER`, `CODEBERG_REPO`, `CODEBERG_BASE_URI` (optional) | **yes** | a write path end to end                       |
| `ObservingRequests`| nothing                                                      | no      | implementing the `Telemetry` port                                   |
| `SharingABackend`  | `CODEBERG_BASE_URI` (optional)                               | no      | `usingBackend`, and who closes what                                 |

`CreatingAnIssue` opens a real issue in a real repository and does not delete it
afterwards. Point it at a scratch repository you own.

Everything else talks anonymously to `https://codeberg.org` and needs outbound
network access. `WalkingPages` deliberately bounds its hand-rolled walks to three
pages so an example does not spend a shared instance's rate-limit budget; its
`PageWalk` section walks a whole listing, so it walks the repository's labels
rather than its issues.

## Printing

`.scalafix.conf` bans the standard library's print-a-line method repo-wide,
because a library that writes to a stream nobody configured is a nuisance to
embed. These programs are the one legitimate exception — showing what a call
returns *is* the point of an example — so every line goes through
`ExampleConsole`, which is the single file in the repository that writes to
standard output. Keeping the exception in one named helper keeps it reviewable.
