package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.PathSegment
import com.worxbend.codeberg4s.SegmentLiteral
import com.worxbend.codeberg4s.ValidationError

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
  * ==Exactly one path segment, which is a limitation of the route==
  *
  * `spec/swagger.v1.json` declares `{name}` as one path parameter and Forgejo routes it as one path segment — unlike
  * `/repos/{owner}/{repo}/branches/{branch}`, whose route is a wildcard and whose values legitimately span several
  * segments. A slash is therefore rejected here, which has a consequence worth stating plainly: '''a rule whose name
  * contains a `/` — a `release/` prefix with a trailing wildcard is the usual shape — cannot be addressed by
  * [[com.worxbend.codeberg4s.repositories.access.RepositoryAccessApi]]'s by-name endpoints at all.'''
  *
  * That is the API's limitation, not this type's. Rejecting the name here trades a request that is certain to answer
  * `404` for an immediate [[ValidationError]] that says why. Percent-encoding the slash into one segment is not
  * attempted: nothing in the spec, and no capture, says the instance would decode it back, and quietly sending
  * `release%2F*` in the hope that it does is exactly the guess this library does not make on a caller's behalf.
  * `listBranchProtections` still returns such a rule, so it can be read — it just cannot be read, edited or deleted one
  * at a time.
  *
  * That is also why a rule '''read back''' is a plain `String` on [[BranchProtection.ruleName]] rather than this type:
  * dropping a rule the instance really holds, because this library cannot address it, would cost the caller the very
  * information they asked for.
  */
opaque type BranchRuleName = String

object BranchRuleName:

  /** Parses a branch protection rule name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank name, a name containing `/`, a name containing a control
    * character, and the traversal segments `.` and `..` — see [[com.worxbend.codeberg4s.repositories.PathSegment]] for
    * why that is a security boundary and not a convenience, and the type's own note for why the slash is rejected
    * rather than encoded.
    *
    * Glob characters are '''not''' rejected: `*` and `?` are what a rule name is made of, and they are legal in a URI
    * path segment.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"branchRuleName"` field
    */
  def from(value: String): Either[ValidationError, BranchRuleName] =
    PathSegment.from("branchRuleName", value)

  /** Builds a rule name from a string literal, checked while the code compiles.
    *
    * `BranchRuleName("...")` '''is''' the rule name, with no `Either` to unwrap: a literal is either valid or it is
    * not, and an invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s, minus the
    * trim — surrounding whitespace is refused rather than removed. See [[com.worxbend.codeberg4s.SegmentLiteral]], and
    * use [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): BranchRuleName =
    SegmentLiteral.plain("branchRuleName", value)

  extension (name: BranchRuleName)

    /** The name as a string, ready to be used as one path segment. */
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

/** The name of a team being granted or denied access to a repository — the `{team}` of
  * `/repos/{owner}/{repo}/teams/{team}`.
  *
  * A team name is unique '''within an organisation''' and nowhere else:
  * [[com.worxbend.codeberg4s.organizations.TeamId]] already records that two organisations may each own a team called
  * `owners`. The route resolves the name against the repository's own organisation, so the name is unambiguous for one
  * request — but it is not an instance-wide identifier the server never reuses, which is why neither of the two
  * mutating team endpoints is retried. See [[RepositoryAccessApi.addTeam]].
  */
opaque type TeamName = String

object TeamName:

  /** Parses a team name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank name, a name containing `/`, a name containing a control
    * character, and the traversal segments `.` and `..`.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"teamName"` field
    */
  def from(value: String): Either[ValidationError, TeamName] =
    PathSegment.from("teamName", value)

  /** Builds a team name from a string literal, checked while the code compiles.
    *
    * `TeamName("...")` '''is''' the team name, with no `Either` to unwrap: a literal is either valid or it is not, and
    * an invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s, minus the trim —
    * surrounding whitespace is refused rather than removed. See [[com.worxbend.codeberg4s.SegmentLiteral]], and use
    * [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): TeamName =
    SegmentLiteral.plain("teamName", value)

  extension (name: TeamName)

    /** The name as a string, ready to be used as one path segment. */
    def value: String = name
