# Examples

`modules/examples` holds runnable programs rather than snippets. They are
compiled by the build under the same `scalacOptions` as the library — `-Werror`
included — so an example that stops matching the API breaks
`./mill modules.__.compile`, and therefore `./verify.sh`, on the commit that
invalidated it. A snippet in a README rots silently; these cannot.

The module is deliberately **not** published. An example is documentation, and
shipping one to Maven Central would put a `main` method on a consumer's
classpath.

## Running them

Every program is run the same way:

```bash
./mill modules.examples.runMain com.worxbend.codeberg4s.examples.<Name>
```

Most of them read from `https://codeberg.org` anonymously and need nothing but
outbound network access. The ones that need a token say so below and in their
own Scaladoc; each reads it from the environment and none of them will invent
credentials for you.

```bash
export CODEBERG_TOKEN=...   # https://codeberg.org/user/settings/applications
```

A note on printing. `.scalafix.conf` bans standard-output printing across this
repository, because a library that writes to a stream nobody configured is a
nuisance to embed. These programs are the one legitimate exception — showing
what a call returns *is* the point of an example — so every line they print
goes through one named helper, `ExampleConsole`, which is the single file a
reviewer has to check.

## The programs

The table below is generated from `modules/examples/src` when this site is
built, so it cannot list a program that does not exist or miss one that does.

<!-- EXAMPLES-TABLE -->

## Reading them in order

If you are new to the library, `HelloCodeberg` then `HandlingErrors` then
`WalkingPages` is the path: the smallest complete program, then what to do when
it fails, then the one operational fact that makes long-running use correct.

The source is browsable at
[`modules/examples/src/com/worxbend/codeberg4s/examples`](https://codeberg.org/worxbend/codeberg4s/src/branch/main/modules/examples/src/com/worxbend/codeberg4s/examples).

## Snippets versus programs

The Scala blocks in [Getting Started](getting-started.md) and in the guides are
compiled by mdoc against the real library when this site is built — a snippet
that does not compile fails the site build with a non-zero exit. That is a
weaker guarantee than the examples module gives, because mdoc compiles a
snippet without running it, and because the site build is not part of
`verify.sh`. Both guarantees are worth having; neither replaces the other.
