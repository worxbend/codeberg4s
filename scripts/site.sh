#!/usr/bin/env bash
#
# Build the codeberg4s documentation microsite.
#
#   scripts/site.sh                 build into out/site/html
#   scripts/site.sh --serve         build, then serve it on http://localhost:8080
#   scripts/site.sh --no-api        skip Scaladoc (much faster while writing prose)
#   scripts/site.sh --clean         discard out/site first
#   scripts/site.sh --draft         allow links to pages that do not exist yet
#
# The pipeline, in order:
#
#   1. resolve the client module's runtime classpath (from Mill, or from
#      CODEBERG4S_SITE_CLASSPATH);
#   2. stage site/src, site/assets and the repository's own documents into one
#      input tree;
#   3. run mdoc over that tree, COMPILING every tagged Scala snippet against the
#      real library — a snippet that does not compile fails this script;
#   4. run Laika over mdoc's output, producing HTML with global link validation;
#   5. generate Scaladoc for the five published modules into <site>/api;
#   6. print where the site is.
#
# Step 3 is the reason this script exists. Documentation that claims an API the
# library does not have is worse than no documentation, and the only way to stop
# that happening is to compile it.
#
# Deliberately NOT wired into build.mill. The library build and this build would
# contend for the same out/ lock, and the site must stay buildable while a test
# run is in flight.

set -euo pipefail

cd "$(dirname "$0")/.."

readonly ROOT="$PWD"

# --------------------------------------------------------------------------
# Versions. Scala must match build.mill's Versions.scala: mdoc compiles snippets
# against the library's TASTy, and a 3.3.x compiler cannot read TASTy written by
# 3.8.4. Coursier resolves the highest version, so naming scala3-compiler
# explicitly on the mdoc launch line is what forces the right one.
readonly SCALA_VERSION="3.8.4"
readonly MDOC_VERSION="2.9.1"
readonly LAIKA_VERSION="1.3.2"

# --------------------------------------------------------------------------
# Where things go. Everything is under out/, which is already gitignored.
readonly SITE_OUT="$ROOT/out/site"
readonly STAGED="$SITE_OUT/staged"
readonly MDOC_OUT="$SITE_OUT/mdoc"
readonly HTML_OUT="$SITE_OUT/html"

readonly SRC="$ROOT/site/src"
readonly ASSETS="$ROOT/site/assets"
readonly LAIKA_DRIVER="$ROOT/site/build/laika.scala"
readonly EXAMPLES_SRC="$ROOT/modules/examples/src/com/worxbend/codeberg4s/examples"

# Note there is no list of documented modules here. Scaladoc is generated from
# whatever the client module's classpath contains, which is exactly the five
# published modules and no more: modules.it and modules.examples are not on it,
# so they cannot be documented by accident, and a sixth published module would
# appear without this script being touched.

# There is no base-path or base-url option, on purpose.
#
# MEASURED: serving the site from a repository subpath needs no configuration.
# Every link Laika renders is relative to the document containing it, including
# the links into the Scaladoc, so out/site/html works unchanged at
# https://example.org/, at https://<user>.github.io/codeberg4s/, at
# https://<user>.codeberg.page/codeberg4s/ and from file:// — verified by reading
# the hrefs out of the generated pages, which are `api/` on the landing page and
# `../api/` one directory down.
#
# Helium's site.baseURL was tried and removed: two builds, one with it and one
# without, produced identical HTML. See the header of site/build/laika.scala.

serve=false
build_api=true
clean=false
draft=false
port=8080

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serve)     serve=true; shift ;;
    --no-api)    build_api=false; shift ;;
    --clean)     clean=true; shift ;;
    --draft)     draft=true; shift ;;
    --port)      port="${2:?--port needs a value}"; shift 2 ;;
    # Print the header block above, whatever length it has grown to — a fixed
    # line range goes stale the first time someone documents a new flag.
    -h|--help)   awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) echo "site.sh: unknown option '$1'" >&2; exit 2 ;;
  esac
done

step=0

announce() {
  step=$((step + 1))
  printf '\n\033[1m── %d. %s\033[0m\n' "$step" "$1"
}

note() { printf '   %s\n' "$1"; }
warn() { printf '\033[33m   warning: %s\033[0m\n' "$1" >&2; }

die() {
  printf '\n\033[31msite.sh FAILED: %s\033[0m\n' "$1" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' is not on PATH, and this script needs it"
}

need cs
need jq
need java

if $clean; then
  rm -rf "$SITE_OUT"
fi

# --------------------------------------------------------------------------
announce "Library classpath"

# `./mill show <task>` prints the task's value as JSON on stdout. For a
# Seq[PathRef] that is an array of strings of the form "ref:v0:<hash>:/path" or
# "qref:v1:<hash>:/path" — the prefix is Mill's cache bookkeeping and has to come
# off. Some Mill versions wrap the value in {"value": ...}; both shapes are
# accepted below rather than guessed at.
#
# CODEBERG4S_SITE_CLASSPATH overrides all of this with a plain colon-separated
# classpath. That exists because Mill takes an exclusive lock on out/, so a site
# build cannot run while a test run is in flight — and because CI may already
# have the classpath from an earlier step.
strip_pathref() { sed -E 's/^q?ref:v[0-9]+:[0-9a-fA-F]+://'; }

if [[ -n "${CODEBERG4S_SITE_CLASSPATH:-}" ]]; then
  classpath="$CODEBERG4S_SITE_CLASSPATH"
  note "using CODEBERG4S_SITE_CLASSPATH (${#classpath} chars)"
else
  [[ -x "$ROOT/mill" ]] || die "no ./mill launcher, and CODEBERG4S_SITE_CLASSPATH is not set"
  note "asking Mill for modules.client.runClasspath"
  raw=$("$ROOT/mill" show modules.client.runClasspath 2>/dev/null) ||
    die "./mill show modules.client.runClasspath failed. If another Mill task holds the out/ lock, wait for it, or set CODEBERG4S_SITE_CLASSPATH to a colon-separated classpath and re-run."
  classpath=$(printf '%s' "$raw" |
    jq -r 'if type == "object" then .value else . end | .[]' |
    strip_pathref |
    paste -sd: -) || die "could not parse Mill's runClasspath JSON"
fi

[[ -n "$classpath" ]] || die "the resolved classpath is empty"

# A classpath that resolves but contains no compiled library is the failure mode
# that would otherwise show up as a hundred confusing mdoc errors.
if ! printf '%s' "$classpath" | tr ':' '\n' | grep -qE '(codeberg4s|/classes)$'; then
  die "the resolved classpath contains no compiled codeberg4s module — refusing to compile snippets against nothing"
fi
note "$(printf '%s' "$classpath" | tr ':' '\n' | wc -l) classpath entries"

# --------------------------------------------------------------------------
announce "Stage the input tree"

rm -rf "$STAGED"
mkdir -p "$STAGED"

[[ -d "$SRC" ]] || die "site/src does not exist"
cp -R "$SRC/." "$STAGED/"

if [[ -d "$ASSETS" ]]; then
  mkdir -p "$STAGED/assets"
  cp -R "$ASSETS/." "$STAGED/assets/"
fi

# The landing page is not a file anyone writes directly. Helium takes the content
# of site/src/landing-page.md, lifts that document out of the tree and re-inserts
# it as the root directory's *title document* — and a title document is what
# renders to index.html.
#
# So a root index.md or README.md is not merely redundant, it is a second writer
# to index.html. Laika renders documents in parallel, and nothing in Laika or in
# this script arbitrates between them: whichever finishes last wins, and if they
# overlap the file ends up holding both. That is not hypothetical. The published
# site once served an index.html that was the landing page written over the first
# half of a second, differently-templated copy of the same page — closing
# </body></html> in the middle, the sidebar's link list dumped into the body
# below it. Both builds exited 0.
#
# Fail here instead, naming the file and where its content belongs.
for collision in index.md README.md; do
  [[ -f "$STAGED/$collision" ]] || continue
  die "site/src/$collision renders to index.html, and so does the generated landing page. Two writers, one file, no arbitration — see the note above homeLink in site/build/laika.scala. Put the front page's prose in site/src/landing-page.md instead."
done

# MEASURED, NOT ASSUMED: Laika 1.3.2 does not resolve a link target written
# `./sibling.md` — it reads `.` as a path segment and reports "unresolved
# internal reference". `sibling.md` resolves; `../other/page.md` resolves.
#
# Codeberg's and GitHub's Markdown renderers both accept the `./` form, and the
# guides are meant to be readable in the repository as well as on the site, so
# the fix belongs here rather than in the prose: strip the prefix in the staged
# copy and leave the sources alone. Only link targets are touched, never text.
#
# MEASURED, NOT ASSUMED, second divergence: an HTML id may not begin with a
# digit, so Laika prefixes an underscore when a heading does. `## 401 with a
# token set` becomes `_401-with-a-token-set` here and `401-with-a-token-set` on
# Codeberg and GitHub. Same problem, same remedy: fix the staged copy so both
# renderings work, and leave the prose written the way its readers will type it.
find "$STAGED" -name '*.md' -exec sed -i -E \
  -e 's/\]\(\.\//](/g' \
  -e 's/\]\(#([0-9])/](#_\1/g' \
  {} +

# Laika's title document for a directory is README.md, and it is what a link to
# `<dir>/README.md` resolves to. The guides and reference pages are written by a
# different work stream, which may reasonably call its title document index.md
# instead; normalise so the cross-links on the pages this script owns cannot
# break either way.
normalise_title_document() {
  local dir="$1"
  if [[ -f "$dir/index.md" && ! -f "$dir/README.md" ]]; then
    mv "$dir/index.md" "$dir/README.md"
    note "$(basename "$dir")/index.md staged as README.md (Laika's title document)"
  fi
}

# A section directory needs a title document: it is what `<dir>/README.md`
# resolves to, and what the sidebar shows for the section. The guides and
# reference pages are written by a different work stream, and a set of numbered
# chapters with no contents page is a perfectly reasonable thing for that stream
# to produce — so generate the contents page from what is actually there, taking
# each entry's title from its own first heading.
#
# When the directory holds nothing at all, say so on the page. A green build that
# quietly shipped an empty section is the thing to avoid, so either case also
# prints a warning naming what was generated.
section_index() {
  local dir="$1" title="$2" empty_note="$3"
  mkdir -p "$dir"

  local pages=()
  local file
  for file in "$dir"/*.md; do
    [[ -f "$file" ]] || continue
    [[ "$(basename "$file")" != "README.md" ]] || continue
    pages+=("$file")
  done

  {
    printf '# %s\n\n' "$title"
    if [[ ${#pages[@]} -eq 0 ]]; then
      printf '%s\n\n' "$empty_note"
    else
      printf 'The pages in this section, in order:\n\n'
      for file in "${pages[@]}"; do
        local heading
        heading=$(grep -m1 -E '^# ' "$file" | sed -E 's/^# +//')
        [[ -n "$heading" ]] || heading=$(basename "$file" .md)
        printf -- '- [%s](%s)\n' "$heading" "$(basename "$file")"
      done
      printf '\n'
    fi
    printf 'This contents page is generated by `scripts/site.sh` from the files in\n'
    printf '`site/src/%s/`, because that directory holds no title document of its own.\n' "$(basename "$dir")"
  } > "$dir/README.md"

  if [[ ${#pages[@]} -eq 0 ]]; then
    warn "$(basename "$dir")/ is empty — its section page says so"
  else
    warn "$(basename "$dir")/ has no title document — generated a contents page listing ${#pages[@]} pages"
  fi
}

# Laika sorts a directory's pages by title, so `01-getting-started.md` through
# `10-troubleshooting.md` come out in alphabetical order of their headings —
# Authentication, Errors, Getting started, Observability … which is not the order
# their author numbered them into. Numbered filenames are an explicit statement of
# reading order, so honour it: generate a navigationOrder from the sorted
# filenames when the directory does not already carry a directory.conf saying
# something else.
order_section() {
  local dir="$1" title="$2"
  [[ -d "$dir" ]] || return 0
  [[ ! -f "$dir/directory.conf" ]] || return 0

  local entries=()
  local file
  for file in "$dir"/*.md; do
    [[ -f "$file" ]] || continue
    entries+=("$(basename "$file")")
  done
  [[ ${#entries[@]} -gt 0 ]] || return 0

  {
    printf 'laika.title = %s\n\n' "$title"
    printf '# Generated by scripts/site.sh: reading order taken from the filenames,\n'
    printf '# because Laika would otherwise sort these pages by heading text.\n'
    printf 'laika.navigationOrder = [\n'
    printf '  %s\n' "${entries[@]}"
    printf ']\n'
  } > "$dir/directory.conf"
}

for section in guides reference; do
  normalise_title_document "$STAGED/$section"
done

if [[ ! -f "$STAGED/guides/README.md" ]]; then
  section_index "$STAGED/guides" "Guides" \
    "The task-oriented guides have not been written yet. Until they are, [Getting Started](../getting-started.md) covers the same ground more briefly."
fi

if [[ ! -f "$STAGED/reference/README.md" ]]; then
  section_index "$STAGED/reference" "Reference" \
    "The lookup pages have not been written yet. Until they are, the [API reference][api] is the complete and authoritative description."
fi

order_section "$STAGED/guides" "Guides"
order_section "$STAGED/reference" "Reference"

# The repository's own documents, copied verbatim so the site works offline and
# so a reader does not have to leave it to find the roadmap or the hazards.
#
# They are copied, not rewritten: they contain relative links to files that exist
# in the repository and not on the site (PLAN.md, spec/, modules/). Laika's link
# validation is therefore switched off for this subtree alone — the pages this
# script owns keep full global validation.
announce "Copy the project documents"

mkdir -p "$STAGED/project/adr"
cat > "$STAGED/project/directory.conf" <<'EOF'
laika.title = Project documents

# These files are copied out of docs/ and the repository root. Their relative
# links point at repository files rather than at site pages, so validating them
# would fail on links that are correct where they were written. Every page
# written *for* the site keeps LinkValidation.Global.
laika.links.validation.scope = off

# Most-useful-first, rather than alphabetical by heading.
laika.navigationOrder = [
  README.md
  ROADMAP.md
  HAZARDS.md
  READINESS.md
  API_INVENTORY.md
  CHANGELOG.md
  VERSIONS.md
  LEDGER.md
  CONSTITUTION_MAPPING.md
  SPEC_PROVENANCE.md
  adr
]
EOF

cat > "$STAGED/project/README.md" <<'EOF'
# Project documents

These are the repository's own documents, copied verbatim when the site is
built. They are written for a contributor rather than for a consumer, and their
relative links point at files in the repository.

- **[Roadmap](ROADMAP.md)** — what is done and what is next, gate by gate.
- **[Hazards](HAZARDS.md)** — the measured divergences between the pinned
  Swagger spec and what Codeberg actually returns. Two of the project's original
  assumptions turned out to be wrong here, which is why the models are built from
  captured fixtures rather than from the spec.
- **[Readiness](READINESS.md)** — an honest gap assessment of what stands
  between this repository and a third party using it.
- **[API inventory](API_INVENTORY.md)** — the endpoint-level checklist.
- **[Changelog](CHANGELOG.md)** — Keep a Changelog format.
- **Architecture decision records** — in `adr/`, starting with ADR-0005 on why
  the API is `Future`-based and ADR-0001 on why the models are hand-written
  rather than generated.
EOF

# GitHub renders `- [x]` as a ticked box. Laika's Markdown parser has no task
# lists, so it reads `[x]` as a reference-style link to an undefined label `x`
# and fails the whole build with several hundred "unresolved link id reference"
# errors. Converting the four checkbox spellings to characters is the smallest
# change that keeps these documents readable in both places, and it is the only
# edit made to them.
copy_project_document() {
  local from="$1" to="$2"
  sed -E 's/\[x\]/✓/g; s/\[X\]/✓/g; s/\[ \]/☐/g' "$from" > "$to"
}

for document in ROADMAP HAZARDS READINESS API_INVENTORY LEDGER CONSTITUTION_MAPPING SPEC_PROVENANCE VERSIONS; do
  if [[ -f "$ROOT/docs/$document.md" ]]; then
    copy_project_document "$ROOT/docs/$document.md" "$STAGED/project/$document.md"
  else
    warn "docs/$document.md is missing — not copied"
  fi
done

if [[ -f "$ROOT/CHANGELOG.md" ]]; then
  copy_project_document "$ROOT/CHANGELOG.md" "$STAGED/project/CHANGELOG.md"
else
  warn "CHANGELOG.md is missing — not copied"
fi

if compgen -G "$ROOT/docs/adr/*.md" > /dev/null; then
  for adr in "$ROOT"/docs/adr/*.md; do
    copy_project_document "$adr" "$STAGED/project/adr/$(basename "$adr")"
  done
  {
    printf 'laika.title = Architecture decision records\n'
    printf 'laika.links.validation.scope = off\n\n'
    printf '# Numbered in the order they were decided.\n'
    printf 'laika.navigationOrder = [\n'
    for adr in "$STAGED"/project/adr/*.md; do printf '  %s\n' "$(basename "$adr")"; done
    printf ']\n'
  } > "$STAGED/project/adr/directory.conf"
else
  warn "docs/adr holds no Markdown — the ADR section will be empty"
fi

note "project documents staged"

# --------------------------------------------------------------------------
announce "Generate the examples table"

# site/src/examples.md carries a marker rather than a hand-written table, so the
# page cannot claim a program that does not exist or miss one that does. The
# purpose column is the first sentence of each program's own Scaladoc, which is
# the sentence its author already had to write.
examples_table() {
  local any=false
  printf '| Program | What it shows | Needs |\n'
  printf '| --- | --- | --- |\n'
  local file
  for file in "$EXAMPLES_SRC"/*.scala; do
    [[ -f "$file" ]] || continue
    grep -q 'def main' "$file" || continue
    any=true
    local name summary needs
    name=$(basename "$file" .scala)
    summary=$(awk '
      { line[NR] = $0 }
      /^object / && !objectLine { objectLine = NR }
      END {
        if (!objectLine) exit
        for (i = objectLine - 1; i > 0; i--) if (line[i] ~ /^\/\*\*/) { start = i; break }
        if (!start) exit
        text = ""
        for (i = start; i <= objectLine; i++) {
          s = line[i]
          sub(/^\/\*\*[ ]?/, "", s)
          sub(/^[ ]*\*\/?[ ]?/, "", s)
          if (s ~ /^[ \t]*$/ && text != "") break
          text = (text == "" ? s : text " " s)
        }
        sub(/\.[ ].*$/, ".", text)
        print text
      }
    ' "$file")
    # Scaladoc markup that has no meaning in Markdown.
    summary=$(printf '%s' "$summary" |
      sed -E "s/\[\[[^]]*\.([A-Za-z0-9_]+)\]\]/\`\1\`/g; s/'''([^']*)'''/**\1**/g; s/\|/\\\\|/g")
    # `|| true` because grep exits 1 when a program needs no environment at all,
    # which is the common case and not a failure.
    needs=$( (grep -oE '"CODEBERG_[A-Z_]+"' "$file" || true) |
      tr -d '"' | sort -u | sed 's/^/`/; s/$/`/' | paste -sd, - | sed 's/,/, /g')
    [[ -n "$needs" ]] || needs="nothing"
    printf '| [`%s`](%s) | %s | %s |\n' \
      "$name" \
      "https://codeberg.org/worxbend/codeberg4s/src/branch/main/modules/examples/src/com/worxbend/codeberg4s/examples/$name.scala" \
      "$summary" \
      "$needs"
  done
  $any || printf '| _none found_ | `modules/examples/src` holds no program with a `main` method. | |\n'
}

if [[ -d "$EXAMPLES_SRC" ]]; then
  table_file="$SITE_OUT/examples-table.md"
  mkdir -p "$SITE_OUT"
  examples_table > "$table_file"
  note "$(($(wc -l < "$table_file") - 2)) example programs found"
else
  warn "modules/examples/src is absent — the examples table will say so"
  table_file="$SITE_OUT/examples-table.md"
  mkdir -p "$SITE_OUT"
  printf '**`modules/examples` does not exist in this checkout.**\n' > "$table_file"
fi

if grep -q '<!-- EXAMPLES-TABLE -->' "$STAGED/examples.md" 2>/dev/null; then
  awk -v table="$table_file" '
    /<!-- EXAMPLES-TABLE -->/ { while ((getline row < table) > 0) print row; next }
    { print }
  ' "$STAGED/examples.md" > "$STAGED/examples.md.tmp"
  mv "$STAGED/examples.md.tmp" "$STAGED/examples.md"
else
  warn "site/src/examples.md has no <!-- EXAMPLES-TABLE --> marker — nothing substituted"
fi

# --------------------------------------------------------------------------
announce "mdoc — compile every tagged snippet"

rm -rf "$MDOC_OUT"
mkdir -p "$MDOC_OUT"

# scala3-compiler is named explicitly so Coursier resolves the version the
# library was built with rather than the older one mdoc depends on.
note "resolving mdoc $MDOC_VERSION with the Scala $SCALA_VERSION compiler"
mdoc_classpath=$(cs fetch --classpath \
  "org.scalameta:mdoc_3:$MDOC_VERSION" \
  "org.scala-lang:scala3-compiler_3:$SCALA_VERSION") ||
  die "could not resolve mdoc — this step needs network access on its first run"

# The library's own flags, so a snippet is held to the same standard as the code
# it documents. -Werror is the point: an unused import in a guide is a defect in
# the guide.
#
# CODEBERG4S_SNIPPET_FLAGS exists for one situation — bisecting which snippet is
# at fault while several documents are being written at once. It weakens the
# guarantee this pipeline exists to provide, so it announces itself loudly and
# must never appear in CI.
readonly STRICT_SNIPPET_FLAGS="-deprecation -feature -Wunused:all -Wvalue-discard -Wnonunit-statement -Werror"
snippet_flags="${CODEBERG4S_SNIPPET_FLAGS:-$STRICT_SNIPPET_FLAGS}"
if [[ "$snippet_flags" != "$STRICT_SNIPPET_FLAGS" ]]; then
  warn "CODEBERG4S_SNIPPET_FLAGS overrides the library's compiler flags — this build proves less than a default one"
  note "using: $snippet_flags"
fi

if ! java -cp "$mdoc_classpath" mdoc.Main \
  --in "$STAGED" \
  --out "$MDOC_OUT" \
  --classpath "$classpath" \
  --scalac-options "$snippet_flags" \
  --report-relative-paths \
  --no-link-hygiene; then
  die "a documentation snippet did not compile against the library (see above). That is the failure this pipeline exists to produce — fix the snippet, do not weaken the check."
fi

note "every tagged snippet compiled"

# --------------------------------------------------------------------------
announce "Laika — render HTML"

rm -rf "$HTML_OUT"
mkdir -p "$HTML_OUT"

[[ -f "$LAIKA_DRIVER" ]] || die "site/build/laika.scala is missing"
need scala-cli

version=$(grep -oE 'val +version +=  *"[^"]+"' "$ROOT/build.mill" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
[[ -n "$version" ]] || version="unreleased"

laika_args=(--in "$MDOC_OUT" --out "$HTML_OUT" --version "$version")
if $draft; then
  laika_args+=(--draft true)
  warn "--draft: link validation is OFF. This build can ship a broken link; a release build must not use it."
fi

scala-cli run "$LAIKA_DRIVER" --server=false -- "${laika_args[@]}" ||
  die "Laika failed. A broken internal link is an error by design (LinkValidation.Global in site/build/laika.scala)."

note "HTML written by Laika $LAIKA_VERSION"

# --------------------------------------------------------------------------
if $build_api; then
  announce "Scaladoc"

  api_out="$HTML_OUT/api"
  rm -rf "$api_out"
  mkdir -p "$api_out"

  # Generated with the Scala 3 scaladoc tool directly rather than with
  # `./mill modules.<m>.docJar`, for two reasons: Mill would take the out/ lock
  # this script exists to avoid, and docJar produces a jar whose unpacked
  # location is a Mill implementation detail that would have to be guessed at.
  #
  # scaladoc's inputs are .tasty files and jars — NOT .scala sources, which it
  # silently ignores. Those .tasty files are exactly the compiled modules already
  # on the classpath resolved in step 1, so they are read back out of it rather
  # than found by walking a directory layout this script would then be coupled
  # to. Documenting the same artefacts mdoc compiled against is also the point:
  # the prose and the API reference cannot describe two different builds.
  #
  # An entry qualifies if it is a directory inside this repository. Third-party
  # jars live in the Coursier cache and are therefore skipped, which is what
  # keeps sttp and upickle out of the generated documentation.
  tasty=()
  while IFS= read -r entry; do
    [[ -d "$entry" ]] || continue
    [[ "$entry" == "$ROOT/"* ]] || continue
    while IFS= read -r -d '' file; do tasty+=("$file"); done \
      < <(find "$entry" -name '*.tasty' -print0)
  done < <(printf '%s' "$classpath" | tr ':' '\n')

  if [[ ${#tasty[@]} -eq 0 ]]; then
    die "no .tasty files on the classpath — compile the library first (./mill modules.__.compile)"
  fi
  note "${#tasty[@]} compiled definitions"

  scaladoc_classpath=$(cs fetch --classpath "org.scala-lang:scaladoc_3:$SCALA_VERSION") ||
    die "could not resolve the scaladoc tool"

  if ! java -cp "$scaladoc_classpath" dotty.tools.scaladoc.Main \
    -d "$api_out" \
    -classpath "$classpath" \
    -project "codeberg4s" \
    -project-version "$version" \
    -no-link-warnings \
    "${tasty[@]}"; then
    die "scaladoc failed"
  fi

  [[ -f "$api_out/index.html" ]] || die "scaladoc produced no index.html in $api_out"
  note "API documentation at $api_out"
else
  announce "Scaladoc — skipped (--no-api)"
  # The pages still link to ./api; there is just nothing there yet.
  warn "the site's API links will 404 until this is run without --no-api"
fi

# --------------------------------------------------------------------------
announce "Done"
printf '   \033[32m%s\033[0m\n' "$HTML_OUT/index.html"

if $serve; then
  # A plain static server, because the site is plain static files. Every link is
  # relative, so the server root is as good a mount point as any other.
  need python3
  printf '\n   http://localhost:%s/\n\n' "$port"
  exec python3 -m http.server "$port" --directory "$HTML_OUT"
fi
