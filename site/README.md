# The documentation site

Source for the codeberg4s microsite. Built by
[`scripts/site.sh`](../scripts/site.sh) into `out/site/html`, which is a plain
static directory: no server-side anything, no build step at serve time.

```bash
scripts/site.sh                # build everything into out/site/html
scripts/site.sh --no-api       # skip Scaladoc; much faster while writing prose
scripts/site.sh --serve        # build, then serve on http://localhost:8080
scripts/site.sh --draft        # allow links to pages that do not exist yet
scripts/site.sh --help         # the pipeline, step by step
```

## What is in here

| Path | What it is | Owner |
| --- | --- | --- |
| `src/landing-page.md` | The prose of the front page, rendered below the header and the teasers. The filename is fixed by Helium, and `src/` must hold no `index.md` or `README.md` — see the note above `homeLink` in `build/laika.scala`. | this directory |
| `src/getting-started.md` | Install, first request, error rails, pagination. | this directory |
| `src/examples.md` | The `modules/examples` programs. Its table is generated at build time. | this directory |
| `src/directory.conf` | Laika configuration for the content tree: title and sidebar order. | this directory |
| `src/guides/` | The task-oriented guides. | the guides work stream |
| `src/reference/` | The lookup pages. | the guides work stream |
| `assets/` | Static files copied to the site root: the favicon, and the stylesheet below. | this directory |
| `assets/css/site.css` | The design layer over Helium — component shapes only. Colours, type scale and metrics are configuration and live in `build/laika.scala`; do not set them in both places. | this directory |
| `build/laika.scala` | The Laika driver: theme, colours, navigation, link validation. | this directory |

Nothing under `site/` is compiled by Mill. `build/laika.scala` is a scala-cli
program, run by `scripts/site.sh`.

## The guarantee

Every Scala block tagged `scala mdoc` — or `mdoc:compile-only`, which is the
usual tag here because a documentation build should not make network calls — is
compiled by mdoc against the real library, under the same compiler flags as the
library itself, `-Werror` included. A snippet that does not compile fails the
script with a non-zero exit.

That is the reason the pipeline exists. Documentation claiming an API the
library does not have is worse than no documentation: it teaches the wrong
thing, confidently.

Laika's link validation is set to `Global`, so a link to a page that does not
exist is also a build failure rather than a 404 discovered by a reader. The one
exclusion is `/api`, the Scaladoc, which Laika does not generate and therefore
cannot see.

Both guarantees are verified by breaking them on purpose:
`Owner("forgejo")` in a snippet fails step 5, and `[x](does-not-exist.md)` fails
step 6.

## Where the site can be served

Nowhere in particular, which is the point. Every link Laika renders — pages,
theme CSS and JavaScript, the favicon and the links into the Scaladoc — is
relative to the document that contains it, so `out/site/html` works unchanged
at:

- a root domain, `https://example.org/`
- a GitHub Pages project site, `https://<user>.github.io/codeberg4s/`
- Codeberg Pages under a repository, `https://<user>.codeberg.page/codeberg4s/`
- `file:///…/out/site/html/index.html`, straight off the filesystem

There is no base-path or base-url option, and its absence is deliberate. Helium
does have a `site.baseURL` setting; building the site with it and without it
produced identical HTML, because it feeds sitemap generation and EPUB/PDF
back-links, neither of which this build produces. It was removed rather than
kept as an option that does nothing. Do not re-add it without an output
difference to point at.

## Deploying

The build produces a directory. Publishing it is a copy:

- **Codeberg Pages** — copy `out/site/html` into the `pages` branch of this
  repository, or into a `pages` repository for a root-domain site.
- **GitHub Pages** — upload `out/site/html` as the Pages artifact.

Neither workflow lives in this directory; both are the CI work stream's.

## Environment

The build needs `cs` (Coursier), `jq`, `java`, `scala-cli`, and `python3` for
`--serve`. On its first run it downloads mdoc, Laika, the Scala 3 compiler and
the scaladoc tool into the Coursier cache; after that it runs offline.

Two escape hatches exist, and both announce themselves:

- `CODEBERG4S_SITE_CLASSPATH` supplies the library classpath directly instead of
  asking Mill for it. Mill takes an exclusive lock on `out/`, so this is how the
  site gets built while a test run is in flight, and how CI reuses a classpath
  it already has.
- `CODEBERG4S_SNIPPET_FLAGS` replaces the compiler flags used for snippets. It
  weakens the guarantee above, so it prints a warning and must not appear in CI.

## Adding a page

Put it in `src/`, link to it from somewhere, and add it to
`laika.navigationOrder` in `src/directory.conf` if it is a top-level page. An
unlisted page still appears in the sidebar; it just sorts alphabetically after
the listed ones.
