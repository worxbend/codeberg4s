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
import laika.ast.Path
import laika.ast.Path.Root
import laika.config.LinkConfig
import laika.config.LinkValidation
import laika.config.SyntaxHighlighting
import laika.config.TargetDefinition
import laika.format.HTML
import laika.format.Markdown
import laika.helium.Helium
import laika.helium.config.Favicon
import laika.helium.config.HeliumIcon
import laika.helium.config.IconLink
import laika.helium.config.Teaser
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

  /** Deep sea blue, which is close enough to Codeberg's own without pretending to be its brand. */
  val primary: Color        = Color.hex("1d4f6e")
  val primaryMedium: Color  = Color.hex("a7c6d9")
  val primaryLight: Color   = Color.hex("edf3f7")
  val secondary: Color      = Color.hex("8a4b1f")
  val text: Color           = Color.hex("1c1f21")
  val background: Color     = Color.hex("ffffff")
  val gradientTop: Color    = Color.hex("1d4f6e")
  val gradientBottom: Color = Color.hex("2e7096")

/** The four claims the landing page is built around. They are the same four the README opens with, deliberately: a
  * reader who arrives from either direction should be told the same thing.
  */
object Teasers:

  val all: Seq[Teaser] = Seq(
    Teaser(
      "A Future API, and nothing else",
      "The public API is scala.concurrent.Future. No effect system leaks into your code and none is added to your "
        + "classpath — the dependency list is sttp client4 and jsoniter-scala.",
    ),
    Teaser(
      "Two error rails",
      "Every operation exists twice. client.repos.get fails the Future with a CodebergException; "
        + "client.repos.attempt.get returns Either[CodebergError, Repository]. Same implementation underneath, so "
        + "the two cannot drift.",
    ),
    Teaser(
      "Illegal requests are unrepresentable",
      "Owners, repository names, branches, tokens and page sizes are opaque types with Either-returning smart "
        + "constructors. A value that would forge a request path is rejected before a client is involved.",
    ),
    Teaser(
      "Pagination you cannot get wrong by accident",
      "No operation returns an unbounded List. Every listing hands back a Page[A] whose nextPage comes from the "
        + "RFC 5988 Link header — never from how many items came back, which is the trap Forgejo's silent limit "
        + "clamp sets for you.",
    ),
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

    val withColours = withMetadata.site
      .themeColors(
        primary       = SiteInfo.primary,
        primaryMedium = SiteInfo.primaryMedium,
        primaryLight  = SiteInfo.primaryLight,
        secondary     = SiteInfo.secondary,
        text          = SiteInfo.text,
        background    = SiteInfo.background,
        bgGradient    = (SiteInfo.gradientTop, SiteInfo.gradientBottom),
      )

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

    val withChrome = withoutRemoteFonts.site
      .favIcons(Favicon.internal(Root / "assets" / "favicon.svg", sizes = "32x32"))
      .site
      .topNavigationBar(
        homeLink = IconLink.internal(Root / "index.md", HeliumIcon.home),
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

    withChrome.site
      .landingPage(
        title              = Some(SiteInfo.title),
        subtitle           = Some("A Scala 3 client for the Codeberg / Forgejo REST API v1"),
        latestReleases     = Seq(ReleaseLine.current(args.version)),
        license            = Some(SiteInfo.licence),
        documentationLinks = Seq(
          TextLink.internal(Root / "getting-started.md", "Getting Started"),
          TextLink.internal(Root / "examples.md", "Examples"),
          TextLink.internal(Args.apiPath, "API (Scaladoc)"),
        ),
        projectLinks       = Seq(
          TextLink.external(SiteInfo.sourceUrl, "Source"),
          TextLink.external(s"${SiteInfo.sourceUrl}/issues", "Issues"),
        ),
        teasers            = Teasers.all,
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
