package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.{PathSegment, SegmentLiteral, ValidationError}

/** The name that addresses one branch protection rule — the `{name}` of
  * `/repos/{owner}/{repo}/branch_protections/{name}`, and the `rule_name` a rule reports.
  *
  * ==A glob, not a branch==
  *
  * A rule protects a '''set''' of branches: `main`, a `release/` prefix with a trailing `*`, and a bare `*` are all
  * legal rule names, and Forgejo matches them against branch names with its own globbing. That is why this is a plain
  * validated string and '''not''' [[com.worxbend.codeberg4s.repositories.BranchName]] — a `BranchName` promises the
  * value names one branch, and a wildcard names none. The one place a real branch appears in this group is the
  * deprecated `branch_name` property, which is typed as a `BranchName` because there the value really is a branch; see
  * [[CreateBranchProtection.legacyBranchName]].
  *
  * ==A slash is allowed, and it travels percent-encoded inside one path segment==
  *
  * `spec/swagger.v1.json` declares `{name}` as one path parameter and Forgejo routes it as one path segment — unlike
  * `/repos/{owner}/{repo}/branches/{branch}`, whose route is a wildcard and whose values legitimately span several
  * segments. A rule name containing `/` — a `release/` prefix with a trailing `*` is the usual shape — therefore cannot
  * be sent as several segments the way a [[com.worxbend.codeberg4s.repositories.BranchName]] is: the router would see
  * `release` and stop matching. It '''can''' be sent as one segment with the slash encoded as `%2F`, because Forgejo
  * reads a `{param}` route value through `PathParam`, which path-unescapes it, and Gitea's own SDK escapes exactly this
  * way. That is what the client does with [[value]]: the transport places the whole name in one segment and
  * percent-encodes the slash.
  *
  * Validation is therefore the '''segmented''' rule rather than the single-segment one: a blank name, a control
  * character, a leading or trailing `/`, an empty segment such as the middle of `a//b`, and a `.` or `..` segment are
  * refused, and a `/` between two real segments is accepted. The traversal check is the one that matters — `..`
  * survives percent-encoding untouched, so without it a name could walk out of the route it was meant for.
  *
  * A rule '''read back''' is a plain `String` on [[BranchProtection.ruleName]] rather than this type: dropping a rule
  * the instance really holds, because this library disliked its spelling, would cost the caller the very information
  * they asked for. Converting in that direction is the caller's explicit step through [[BranchRuleName.from]].
  */
opaque type BranchRuleName = String

object BranchRuleName:

  /** Parses a branch protection rule name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank name, a name containing a control character, a leading or
    * trailing `/`, an empty `/`-separated part, and the traversal segments `.` and `..` — see
    * [[com.worxbend.codeberg4s.PathSegment.segmented]] for why that is a security boundary and not a convenience, and
    * the type's own note for why a `/` between two parts is accepted and encoded rather than refused.
    *
    * Glob characters are '''not''' rejected: `*` and `?` are what a rule name is made of, and they are legal in a URI
    * path segment.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"branchRuleName"` field
    */
  def from(value: String): Either[ValidationError, BranchRuleName] =
    PathSegment.segmented("branchRuleName", value)

  /** Builds a rule name from a string literal, checked while the code compiles.
    *
    * `BranchRuleName("...")` '''is''' the rule name, with no `Either` to unwrap: a literal is either valid or it is
    * not, and an invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s, minus the
    * trim — surrounding whitespace is refused rather than removed. See [[com.worxbend.codeberg4s.SegmentLiteral]], and
    * use [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): BranchRuleName =
    SegmentLiteral.segmented("branchRuleName", value)

  extension (name: BranchRuleName)

    /** The name as a string, sent whole as '''one''' path segment — a `/` in it is percent-encoded, never split. */
    def value: String = name

/** The glob a tag protection rule matches tags with — the `name_pattern` of a `TagProtection`.
  *
  * `v*`, `v1.*` and `*` are the usual shapes. Unlike [[BranchRuleName]] this never becomes a path segment: a tag
  * protection is addressed by [[TagProtectionId]], so the pattern only ever travels in a request body. Validation is
  * correspondingly narrower — it rejects what cannot be a useful pattern rather than what cannot be a path.
  *
  * A pattern '''read back''' is a plain `String` on [[TagProtection.namePattern]], for the reason [[BranchRuleName]]
  * gives: a rule the instance holds must not disappear from a listing because this library disliked its spelling.
  */
opaque type TagNamePattern = String

object TagNamePattern:

  /** The field name a rejected value is reported under, stable enough for a caller to branch on. */
  private val Field: String = "tagNamePattern"

  /** Parses a tag name pattern.
    *
    * Trims surrounding whitespace. Rejects an empty or blank pattern and a pattern containing a control character. A
    * blank pattern is worth rejecting here rather than remotely: Forgejo would answer `422`, and a protection rule that
    * silently matched nothing while the caller believed a tag namespace was protected is precisely the failure this
    * group exists to prevent.
    *
    * A `/` is '''accepted''': it never becomes a path segment, and a Git tag may legitimately contain one.
    *
    * @return
    *   the trimmed pattern, or a [[ValidationError]] on the `"tagNamePattern"` field
    */
  def from(value: String): Either[ValidationError, TagNamePattern] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError(Field, "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(Field, "must not contain a control character"))
    else Right(trimmed)

  extension (pattern: TagNamePattern)

    /** The pattern as a string, as it is sent in a request body. */
    def value: String = pattern

/** The slug by which a team is named on the repository access surface — the `{team}` of
  * `/repos/{owner}/{repo}/teams/{team}`, and an entry of a branch or tag protection's team whitelist.
  *
  * ==Why a slug, and why not [[com.worxbend.codeberg4s.organizations.TeamName]]==
  *
  * `com.worxbend.codeberg4s.organizations.TeamName` is the name a caller '''supplies''' when creating or renaming a
  * team, and it may contain a `/` because it only ever travels in a request body. This type is the same team as the
  * '''address''' Forgejo resolves it by — one path segment on the team routes, and one array entry on the
  * `push_whitelist_teams`, `merge_whitelist_teams`, `approvals_whitelist_teams` and `whitelist_teams` properties, where
  * Forgejo looks the entry up by name within the repository's organisation. The two used to share a name, which made
  * `import …organizations.TeamName` and `import …repositories.access.TeamName` in one file a compile error and a
  * reader's puzzle; this one is now `TeamSlug`.
  *
  * A team slug is unique '''within an organisation''' and nowhere else:
  * [[com.worxbend.codeberg4s.organizations.TeamId]] already records that two organisations may each own a team called
  * `owners`. The route resolves the slug against the repository's own organisation, so the value is unambiguous for one
  * request — but it is not an instance-wide identifier the server never reuses, which is why neither of the two
  * mutating team endpoints is retried. See [[RepositoryAccessApi.addTeam]].
  *
  * A whitelist entry '''read back''' is a plain `String` on [[BranchProtection]] and [[TagProtection]], for the reason
  * [[BranchRuleName]] gives: a team the instance really lists must not vanish because this library disliked its
  * spelling.
  */
opaque type TeamSlug = String

object TeamSlug:

  /** Parses a team slug.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..`. The slash rule holds for the whitelist entries as much
    * as for the path: a whitelist line Forgejo cannot resolve to a team is an entry that grants nothing while looking
    * as though it grants something.
    *
    * @return
    *   the trimmed slug, or a [[ValidationError]] on the `"teamSlug"` field
    */
  def from(value: String): Either[ValidationError, TeamSlug] =
    PathSegment.from("teamSlug", value)

  /** Builds a team slug from a string literal, checked while the code compiles.
    *
    * `TeamSlug("...")` '''is''' the slug, with no `Either` to unwrap: a literal is either valid or it is not, and an
    * invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s, minus the trim —
    * surrounding whitespace is refused rather than removed. See [[com.worxbend.codeberg4s.SegmentLiteral]], and use
    * [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): TeamSlug =
    SegmentLiteral.plain("teamSlug", value)

  extension (slug: TeamSlug)

    /** The slug as a string, ready to be used as one path segment or one whitelist entry. */
    def value: String = slug
