//> using scala 3.8.4
//> using dep org.typelevel::laika-io:1.3.2
//> using options -deprecation -feature -Wunused:all -Wvalue-discard -Wnonunit-statement -Werror

/** Laika driver for the codeberg4s documentation microsite.
  *
  * Run by `scripts/site.sh`, never by `build.mill` — the Mill build owns the library modules only, and the site must be
  * buildable without taking the `out/` lock that the library build holds.
  *
  * Usage:
  * {{{
  * scala-cli run site/build/laika.scala -- --in <dir> --out <dir> --version <v> [--draft true]
  * }}}
  *
  * ==Serving the site from a subpath==
  *
  * Nothing has to be configured for that. Every link Laika renders — pages, theme CSS and JavaScript, the favicon and
  * the Scaladoc links defined below — comes out relative to the document that contains it, so the finished directory
  * works unchanged at `https://example.org/`, at `https://<user>.github.io/codeberg4s/` and at
  * `https://<user>.codeberg.page/codeberg4s/`. Opening `out/site/html/index.html` straight off the filesystem exercises
  * the same property.
  *
  * There is deliberately no base-path or base-url option, and that is a measured decision rather than an omission.
  * Helium's `site.baseURL` sets Laika's `siteBaseURL`, which feeds sitemap generation and EPUB/PDF back-links — neither
  * of which this build produces. Building the site twice, once with it and once without, produced byte-identical HTML.
  * Helium's canonical link comes from per-document metadata instead, so a single site-wide value could not supply it.
  * The option was removed rather than kept as decoration; do not re-add it without an output difference to point at.
  */

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import laika.api.Transformer
import laika.ast.LengthUnit.px
import laika.ast.Path
import laika.ast.Path.Root
import laika.config.LinkConfig
import laika.config.LinkValidation
import laika.config.SyntaxHighlighting
import laika.config.TargetDefinition
import laika.format.HTML
import laika.format.Markdown
import laika.helium.Helium
import laika.helium.config.AnchorPlacement
import laika.helium.config.ButtonLink
import laika.helium.config.ColorQuintet
import laika.helium.config.Favicon
import laika.helium.config.HeliumIcon
import laika.helium.config.IconLink
import laika.helium.config.TextLink
import laika.io.syntax.*
import laika.theme.ThemeProvider
import laika.theme.config.Color

/** Everything about the site that is not a page. */
object SiteInfo:
  val title: String       = "codeberg4s"
  val description: String = "A Scala 3 client for the Codeberg / Forgejo REST API v1, with a Future-based public API."
  val sourceUrl: String   = "https://codeberg.org/worxbend/codeberg4s"
  val licence: String     = "MIT"

  /** The Helium defaults that would be fetched from a Google server on every page view. */
  val remoteFontFamilies: Set[String] = Set("Lato", "Fira Mono")

  /** System font stacks, so a page needs no network to look right. */
  val bodyFont: String =
    "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"

  val codeFont: String =
    "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace"

/** The colour system.
  *
  * Dark is the mode this site is designed for; light is derived from it rather than the other way round. Both are
  * defined here in full, because Helium generates one `:root` block per scheme and a value left unset falls back to a
  * Helium default that was chosen for a different palette.
  *
  * One accent hue, azure, in two tones — the bright one carries interactive text on near-black, the deep one carries it
  * on white. Keeping to a single hue is what stops a documentation site turning into a colour chart; the blue continues
  * the identity the project already had, without claiming to be Codeberg's brand.
  *
  * Every text-on-background pair below clears WCAG AA (4.5:1 for body text, 3:1 for large text). The tightest pair is
  * `muted` on `background`, at 7.0:1 dark and 5.9:1 light, so the small print is legible rather than merely present.
  */
object Palette:

  /** Near-black with a trace of blue in it. Flat: the header takes this as both gradient stops, so there is no gradient
    * — see [[Site.theme]].
    */
  object Dark:
    val background: Color  = Color.hex("0b0f14")
    val surface: Color     = Color.hex("131a22")
    val border: Color      = Color.hex("26313d")
    val text: Color        = Color.hex("e6edf5")
    val accent: Color      = Color.hex("4cc2ff")
    val accentHover: Color = Color.hex("9adcff")

  object Light:
    val background: Color  = Color.hex("ffffff")
    val surface: Color     = Color.hex("f4f7fa")
    val border: Color      = Color.hex("d5dee7")
    val text: Color        = Color.hex("0f1720")
    val accent: Color      = Color.hex("0a6a9c")
    val accentHover: Color = Color.hex("064a6e")

  /** The landing page header stays near-black in both schemes. A hero that inverts with the colour scheme gives the
    * site two different first impressions; this way it has one.
    */
  val heroBackground: Color = Dark.background

  /** Syntax highlighting, shared by both schemes because the code block keeps its dark surface in both.
    *
    * Helium takes two quintets. `base` is structural — c1 is the block background, c2 comments, c5 ordinary code text.
    * `wheel` is the token colours: keywords, declarations, literals, strings, and type names, in that order.
    */
  val syntaxBase: ColorQuintet = ColorQuintet(
    Color.hex("0f1620"), // block background — a shade off the page, so the block reads as a surface
    Color.hex("6b7a8c"), // comments
    Color.hex("8b9bb0"),
    Color.hex("b7c7da"),
    Color.hex("e6edf5"), // ordinary code text
  )

  val syntaxWheel: ColorQuintet = ColorQuintet(
    Color.hex("ff7b9c"), // keywords
    Color.hex("f5a97f"), // declaration names
    Color.hex("ffd479"), // literals and numbers
    Color.hex("a6e3a1"), // strings
    Color.hex("7fd3ff"), // type names
  )

/** Command-line arguments, parsed rather than positional so the shell script reads clearly. */
final case class Args(
    in: String,
    out: String,
    version: String,
    draft: Boolean,
)

object Args:

  private val usage: String =
    "usage: laika.scala --in <dir> --out <dir> --version <version> [--draft true]"

  /** A deliberately small parser: three required named options and one optional flag, no positional forms, no
    * abbreviations.
    */
  def parse(argv: List[String]): Either[String, Args] =
    def loop(rest: List[String], acc: Map[String, String]): Either[String, Map[String, String]] =
      rest match
        case Nil                                            => Right(acc)
        case flag :: value :: tail if flag.startsWith("--") => loop(tail, acc.updated(flag.drop(2), value))
        case other :: _                                     => Left(s"unexpected argument '$other'\n$usage")

    def required(values: Map[String, String], name: String): Either[String, String] =
      values.get(name).toRight(s"missing --$name\n$usage")

    for
      values  <- loop(argv, Map.empty)
      in      <- required(values, "in")
      out     <- required(values, "out")
      version <- required(values, "version")
    yield Args(
      in      = in,
      out     = out,
      version = version,
      draft   = values.get("draft").exists(value => value.equalsIgnoreCase("true")),
    )

  /** Where `scripts/site.sh` puts the Scaladoc, as a path in Laika's virtual tree.
    *
    * Laika does not generate those files, so it cannot validate the target — which is why the path is excluded from
    * link validation below. It is still declared as an internal path rather than as an external URL, because that is
    * what makes Laika render it relative to whichever page links to it, and therefore what makes the site work under
    * any URL prefix.
    */
  val apiPath: Path = Root / "api" / "index.html"

  /** The subtree the Scaladoc occupies. Everything under it is off-limits to link validation. */
  val apiRoot: Path = Root / "api"

object Site:

  /** Helium, configured rather than replaced. Hand-writing a theme would be a week of CSS to end up with a worse
    * version of this one.
    */
  def theme(args: Args): ThemeProvider =
    val withMetadata = Helium.defaults.site
      .metadata(
        title       = Some(SiteInfo.title),
        description = Some(SiteInfo.description),
        language    = Some("en"),
        version     = Some(args.version),
      )

    // Helium's colour slots are named after its own defaults rather than after what they do, so the mapping is worth
    // stating once: `primary` colours headings, links and navigation; `primaryLight` is the fill behind panels and the
    // sidebar; `primaryMedium` is every border; `secondary` is the hover state. `bgGradient` gets the same colour twice
    // on purpose — that is how Helium is told to draw a flat header rather than a gradient one.
    val withColours = withMetadata.site
      .themeColors(
        primary       = Palette.Light.accent,
        primaryMedium = Palette.Light.border,
        primaryLight  = Palette.Light.surface,
        secondary     = Palette.Light.accentHover,
        text          = Palette.Light.text,
        background    = Palette.Light.background,
        bgGradient    = (Palette.heroBackground, Palette.heroBackground),
      )
      .site
      .darkMode
      .themeColors(
        primary       = Palette.Dark.accent,
        primaryMedium = Palette.Dark.border,
        primaryLight  = Palette.Dark.surface,
        secondary     = Palette.Dark.accentHover,
        text          = Palette.Dark.text,
        background    = Palette.Dark.background,
        bgGradient    = (Palette.heroBackground, Palette.heroBackground),
      )
      .site
      .syntaxHighlightingColors(base = Palette.syntaxBase, wheel = Palette.syntaxWheel)
      .site
      .darkMode
      .syntaxHighlightingColors(base = Palette.syntaxBase, wheel = Palette.syntaxWheel)

    // Helium's default typography is Lato and Fira Mono, pulled from
    // fonts.googleapis.com by a <link> in every page. Two reasons not to keep it: a documentation site should not make
    // every reader's browser announce itself to a third party, and a site built to be readable offline should not go
    // half-blank without a network. Removing just those two definitions leaves Helium's own icon font, which is
    // bundled and which the navigation needs.
    val withoutRemoteFonts = withColours.site
      .removeFontResources(font => SiteInfo.remoteFontFamilies.contains(font.family))
      .site
      .fontFamilies(
        body      = SiteInfo.bodyFont,
        headlines = SiteInfo.bodyFont,
        code      = SiteInfo.codeFont,
      )

    // Helium's defaults are a 15px body in an 860px column, with headings that step 34 / 28 / 20 / 15. Two problems for
    // a page that is mostly prose about code: 15px is small for long-form reading at arm's length, and a 20px h3 next
    // to a 15px h4 gives the reader no way to see the level of a heading without counting.
    //
    // 16px body, and a scale that keeps a visible ratio at every step. The column narrows rather than widens, because
    // the constraint on a text column is the eye's return sweep, not the screen: ~75 characters at this size.
    val withTypography = withoutRemoteFonts.site
      .fontSizes(
        body    = px(16),
        code    = px(14),
        title   = px(40),
        header2 = px(27),
        header3 = px(20),
        header4 = px(16),
        small   = px(13),
      )
      .site
      .layout(
        contentWidth        = px(820),
        navigationWidth     = px(280),
        topBarHeight        = px(48),
        defaultBlockSpacing = px(14),
        defaultLineHeight   = 1.65,
        anchorPlacement     = AnchorPlacement.Right,
      )

    // The design lives in site/assets/css/site.css, not here.
    //
    // Helium exposes its palette and its metrics as configuration — that is everything above — but not its component
    // shapes: the header is a centred block, a code block is a rectangle with no border, a table has no rules.
    // Those are CSS, so they are changed in CSS, and `internalCSS` points at a directory in the input tree whose
    // stylesheets are linked after Helium's own. Later in the cascade, same specificity, so an override is an override
    // and nothing needs `!important`.
    //
    // scripts/site.sh copies site/assets to the staged tree, which is why the path is /assets and not /site/assets.
    val withStyles = withTypography.site.internalCSS(Root / "assets" / "css")

    val withChrome = withStyles.site
      .favIcons(Favicon.internal(Root / "assets" / "favicon.svg", sizes = "32x32"))
      .site
      .topNavigationBar(
        // `Root / "README"`, with no `.md` and no such file in site/src, is not a typo.
        //
        // MEASURED, NOT ASSUMED. The landing page configured below is not a page anyone writes directly. Helium takes
        // the content of `site/src/landing-page.md`, lifts that document out of the content tree, and re-inserts it as
        // the *title document* of the root directory — at the suffix-less path `/README`, because `README` is Laika's
        // default title-document input name. A directory's title document is what renders to `index.html`, which is how
        // the landing page comes to be the site's front page.
        //
        // So this is the only path that resolves to the front page. `Root / "landing-page.md"` fails link validation,
        // because by the time links are resolved that document is gone from the tree. `Root / "README.md"` fails too:
        // the re-inserted document carries no suffix.
        //
        // The consequence to keep in mind is that site/src must contain no `index.md` and no `README.md`. Either one
        // would also render to `index.html`, and Laika renders documents in parallel — so the two writers race for the
        // same file. That is not hypothetical: it is what produced a published `index.html` holding the landing page
        // spliced on top of the tail of a second, differently-templated copy of the same page. scripts/site.sh fails
        // the build if either file reappears.
        homeLink = IconLink.internal(Root / "README", HeliumIcon.home),
        navLinks = Seq(
          TextLink.internal(Root / "getting-started.md", "Getting Started"),
          TextLink.internal(Root / "examples.md", "Examples"),
          TextLink.internal(Args.apiPath, "API"),
          IconLink.external(SiteInfo.sourceUrl, HeliumIcon.github),
        ),
      )
      .site
      .mainNavigation(depth = 3, includePageSections = false)
      .site
      .pageNavigation(depth = 3)

    // Everything below the header is the prose of `site/src/landing-page.md`. That file has no
    // top-level heading of its own on purpose: `title` here already renders "codeberg4s" directly above it, and a
    // second `<h1>codeberg4s</h1>` under it said the same word twice.
    //
    // The landing page carries no navigation bar — Helium renders it from a template of its own, which has no top bar
    // in it. `titleLinks` is the replacement: a reader who lands on the front page needs a way into the documentation
    // that is not "scroll to the bottom and hope", and these three cover the three things anyone arrives wanting.
    withChrome.site
      .landingPage(
        title          = Some(SiteInfo.title),
        subtitle       = Some("A Scala 3 client for the Codeberg / Forgejo REST API v1"),
        titleLinks     = Seq(
          ButtonLink.internal(Root / "getting-started.md", "Get started"),
          TextLink.internal(Root / "examples.md", "Examples"),
          TextLink.internal(Args.apiPath, "API reference"),
          IconLink.external(SiteInfo.sourceUrl, HeliumIcon.github),
        ),
        latestReleases = Seq(ReleaseLine.current(args.version)),
        license        = Some(SiteInfo.licence),
        // No `documentationLinks`. Helium renders them as a boxed panel in the header's right-hand column, and every
        // entry it would hold is already a button in `titleLinks` two inches to the left. Saying the same three things
        // twice in one header is worse than saying them once, and the panel was tall enough to leave the left-hand
        // column looking abandoned next to it.
        projectLinks   = Seq(
          TextLink.external(SiteInfo.sourceUrl, "Source"),
          TextLink.external(s"${SiteInfo.sourceUrl}/issues", "Issues"),
        ),
        // No `teasers`. The four they held were the four bold paragraphs of `landing-page.md` in compressed form, so
        // a reader met the same four claims twice on one screen. The prose is the version worth keeping: it is the one
        // with the `Owner`/`Owner.from` contrast, the "pick one per call site" instruction and the link to the clamp
        // hazard in it, and a Helium `Teaser` is a (title, description) pair rendered as plain text, so it can carry
        // none of those. `teasers` defaults to `Nil`.
      )
      .site
      .footer(
        s"codeberg4s — ${SiteInfo.licence} licensed. Built with Laika; every Scala snippet on this site is compiled "
          + "by mdoc against the library it documents."
      )
      .build

  /** Named link targets, so a page can write `[the API reference][api]` without repeating a path.
    *
    * `api` is internal on purpose: Laika renders internal targets relative to the linking document, which is what lets
    * the finished site move between a root domain and a repository subpath untouched. Laika cannot see those files, so
    * the subtree is excluded from validation in [[validation]] — the one exclusion on the whole site.
    */
  val links: LinkConfig =
    LinkConfig.empty.addTargets(
      TargetDefinition.internal("api", Args.apiPath),
      TargetDefinition.external("source", SiteInfo.sourceUrl),
    )

  /** Global link validation is the point of this build: a link to a page that does not exist fails the transformation
    * rather than shipping a 404 to a reader.
    *
    * Draft mode turns it off. That is for the hour in which several documents are being written at once and half the
    * cross-links point at pages that do not exist yet; it is never how the published site is built, and
    * `scripts/site.sh` says so on every draft run.
    */
  def validation(args: Args): LinkValidation =
    if args.draft then LinkValidation.Off
    else LinkValidation.Global(excluded = Seq(Args.apiRoot))

  def transform(args: Args): IO[Unit] =
    val configured = Transformer
      .from(Markdown)
      .to(HTML)
      .using(Markdown.GitHubFlavor, SyntaxHighlighting)
      .withConfigValue(links)
      .withConfigValue(validation(args))

    configured
      .parallel[IO]
      .withTheme(theme(args))
      .build
      .use(_.fromDirectory(args.in).toDirectory(args.out).transform)
      .void

/** `ReleaseInfo` is a two-field record; wrapping it keeps the label wording in one place. */
object ReleaseLine:
  import laika.helium.config.ReleaseInfo
  def current(version: String): ReleaseInfo = ReleaseInfo("Current version", version)

object LaikaSite extends IOApp:

  override def run(argv: List[String]): IO[ExitCode] =
    Args.parse(argv) match
      case Left(problem) => IO.println(s"laika.scala: $problem").as(ExitCode.Error)
      case Right(args)   => Site.transform(args).as(ExitCode.Success)
