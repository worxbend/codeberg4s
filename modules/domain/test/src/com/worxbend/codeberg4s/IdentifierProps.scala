package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.issues.CommentId
import com.worxbend.codeberg4s.issues.IssueNumber
import com.worxbend.codeberg4s.issues.LabelColor
import com.worxbend.codeberg4s.issues.LabelId
import com.worxbend.codeberg4s.issues.LabelName
import com.worxbend.codeberg4s.issues.MilestoneId
import com.worxbend.codeberg4s.issues.MilestoneTitle
import com.worxbend.codeberg4s.miscellaneous.MarkdownContext
import com.worxbend.codeberg4s.notifications.NotificationThreadId
import com.worxbend.codeberg4s.notifications.UnreadCount
import com.worxbend.codeberg4s.organizations.OrgName
import com.worxbend.codeberg4s.organizations.TeamId
import com.worxbend.codeberg4s.pulls.PullRequestNumber
import com.worxbend.codeberg4s.pulls.ReviewId
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.ReleaseId
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.TagName
import com.worxbend.codeberg4s.users.Username

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.Prop.forAll

import java.util.Locale

/** One opaque string type, reduced to what a property needs to talk about it.
  *
  * The opaque types are all distinct, so nothing can hold a heterogeneous list of them; what the properties below
  * actually need is the `String => Either[ValidationError, String]` each smart constructor induces, plus the promise
  * the accepted value makes. Erasing to that is what lets twelve types share six properties instead of each growing its
  * own copy.
  *
  * @param name
  *   the type's own name, so a counterexample says which constructor broke
  * @param field
  *   the `ValidationError.field` this type blames, which callers branch on
  * @param valid
  *   values the constructor must accept unchanged
  * @param invariant
  *   what an accepted value promises — checked against every accepted value, however hostile the input
  * @param parse
  *   the smart constructor, followed by `value`
  */
private final case class StringIdentifier(
    name: String,
    field: String,
    valid: Gen[String],
    invariant: String => Boolean,
    parse: String => Either[ValidationError, String],
)

/** One opaque `Long` identifier, erased the same way.
  *
  * Unlike [[StringIdentifier]] the blamed field is not stated: every one of these types reports its own name
  * decapitalised, and deriving it rather than repeating it turns the convention itself into something the properties
  * check.
  *
  * @param minimum
  *   the smallest value the constructor accepts
  */
private final case class NumericIdentifier(
    name: String,
    minimum: Long,
    parse: Long => Either[ValidationError, Long],
):

  /** The `ValidationError.field` this type is expected to blame. */
  def field: String = s"${name.head.toLower}${name.tail}"

/** The smart constructors of every opaque type in `domain`, checked as a family.
  *
  * Two questions are asked of each of them. The '''round trip''' — does a value the constructor accepts survive `from`
  * and `value` unchanged, is trimming the only normalisation, and is parsing an accepted value again a no-op? And the
  * '''security invariant''' — for arbitrary input, including deliberate traversal and header-splitting attempts, does
  * every value the constructor accepts still satisfy the promise the type's Scaladoc makes? The second is the one worth
  * having: `Owner`, `BranchName` and `ContentPath` are interpolated into request paths, and their constructors are the
  * only thing standing between a caller's string and a forged URI.
  */
final class IdentifierProps extends PropertyBase:

  private val hexText: Gen[String] =
    Gen.choose(4, 64).flatMap(length => Gen.listOfN(length, PropertyBase.hexDigit)).map(_.mkString)

  private val colourText: Gen[String] =
    Gen.oneOf(3, 6).flatMap(length => Gen.listOfN(length, PropertyBase.hexDigit)).map(_.mkString)

  private val segmentedText: Gen[String] =
    Gen.choose(1, 4).flatMap(count => Gen.listOfN(count, PropertyBase.plainSegment)).map(_.mkString("/"))

  private def isTrimmed(value: String): Boolean = value.trim.equals(value)

  private def isHexadecimal(value: String): Boolean = value.forall(digit => "0123456789abcdef".contains(digit))

  /** Types that must occupy exactly one URI path segment: no slash gets through, at any cost. */
  private val singleSegment: Vector[StringIdentifier] =
    val promise: String => Boolean =
      value => value.nonEmpty && !value.contains('/') && !value.exists(_.isControl) && isTrimmed(value)
    Vector(
      StringIdentifier("Owner", "owner", PropertyBase.plainSegment, promise, text => Owner.from(text).map(_.value)),
      StringIdentifier(
        "RepoName",
        "repoName",
        PropertyBase.plainSegment,
        promise,
        text                                                                      => RepoName.from(text).map(_.value),
      ),
      StringIdentifier(
        "Username",
        "username",
        PropertyBase.plainSegment,
        promise,
        text                                                                      => Username.from(text).map(_.value),
      ),
      StringIdentifier(
        "OrgName",
        "orgName",
        PropertyBase.plainSegment,
        promise,
        text                                                                      => OrgName.from(text).map(_.value),
      ),
    )

  /** Types that legitimately span several segments, and therefore have to reject traversal themselves. */
  private val segmented: Vector[StringIdentifier] =
    val promise: String => Boolean = value =>
      value.nonEmpty &&
      !value.exists(_.isControl) &&
      !value.startsWith("/") &&
      !value.endsWith("/") &&
      isTrimmed(value) &&
      value.split('/').forall(part => part.nonEmpty && !part.equals(".") && !part.equals(".."))
    Vector(
      StringIdentifier("BranchName", "branch", segmentedText, promise, text    => BranchName.from(text).map(_.value)),
      StringIdentifier("TagName", "tag", segmentedText, promise, text          => TagName.from(text).map(_.value)),
      StringIdentifier("ContentPath", "filepath", segmentedText, promise, text => ContentPath.from(text).map(_.value)),
    )

  /** Types joined into a comma-separated query parameter, where a comma would silently become a second filter. */
  private val filterTokens: Vector[StringIdentifier] =
    val promise: String => Boolean =
      value => value.nonEmpty && !value.contains(',') && !value.exists(_.isControl) && isTrimmed(value)
    Vector(
      StringIdentifier(
        "LabelName",
        "labelName",
        PropertyBase.plainSegment,
        promise,
        text => LabelName.from(text).map(_.value),
      ),
      StringIdentifier(
        "MilestoneTitle",
        "milestoneTitle",
        PropertyBase.plainSegment,
        promise,
        text => MilestoneTitle.from(text).map(_.value),
      ),
    )

  /** Types that carry free text but still must not corrupt the request that carries them. */
  private val freeText: Vector[StringIdentifier] =
    val promise: String => Boolean =
      value => value.nonEmpty && !value.exists(_.isControl) && isTrimmed(value)
    Vector(
      StringIdentifier(
        "UserAgent",
        "userAgent",
        PropertyBase.plainSegment,
        promise,
        text => UserAgent.from(text).map(_.value),
      ),
      StringIdentifier(
        "MarkdownContext",
        "markdownContext",
        PropertyBase.plainSegment,
        promise,
        text => MarkdownContext.from(text).map(_.value),
      ),
    )

  /** Types that normalise as well as validate: case for a commit id, an optional `#` for a colour. */
  private val normalising: Vector[StringIdentifier] =
    Vector(
      StringIdentifier(
        "CommitSha",
        "commitSha",
        hexText,
        value => value.length >= 4 && value.length <= 64 && isHexadecimal(value),
        text  => CommitSha.from(text).map(_.value),
      ),
      StringIdentifier(
        "LabelColor",
        "labelColor",
        colourText,
        value => Vector(3, 6).contains(value.length) && isHexadecimal(value),
        text  => LabelColor.from(text).map(_.value),
      ),
    )

  private val allStringTypes: Vector[StringIdentifier] =
    singleSegment ++ segmented ++ filterTokens ++ freeText ++ normalising

  private val numericTypes: Vector[NumericIdentifier] = Vector(
    NumericIdentifier("IssueNumber", 1L, text          => IssueNumber.from(text).map(_.value)),
    NumericIdentifier("CommentId", 1L, text            => CommentId.from(text).map(_.value)),
    NumericIdentifier("LabelId", 1L, text              => LabelId.from(text).map(_.value)),
    NumericIdentifier("MilestoneId", 1L, text          => MilestoneId.from(text).map(_.value)),
    NumericIdentifier("ReleaseId", 1L, text            => ReleaseId.from(text).map(_.value)),
    NumericIdentifier("ReviewId", 1L, text             => ReviewId.from(text).map(_.value)),
    NumericIdentifier("TeamId", 1L, text               => TeamId.from(text).map(_.value)),
    NumericIdentifier("PullRequestNumber", 1L, text    => PullRequestNumber.from(text).map(_.value)),
    NumericIdentifier("NotificationThreadId", 1L, text => NotificationThreadId.from(text).map(_.value)),
    NumericIdentifier("UnreadCount", 0L, text          => UnreadCount.from(text).map(_.value)),
  )

  private val anyStringType: Gen[StringIdentifier] = Gen.oneOf(allStringTypes)

  private val anyNumericType: Gen[NumericIdentifier] = Gen.oneOf(numericTypes)

  /** A type together with a value it must accept unchanged. */
  private val validValue: Gen[(StringIdentifier, String)] =
    for
      identifier <- anyStringType
      text       <- identifier.valid
    yield (identifier, text)

  /** A type, a value padded with whitespace, and the value the constructor must recover from it. */
  private val paddedValue: Gen[(StringIdentifier, String, String)] =
    for
      identifier <- anyStringType
      text       <- identifier.valid
      before     <- PropertyBase.padding
      after      <- PropertyBase.padding
    yield (identifier, s"$before$text$after", text)

  private val anyLong: Gen[Long] =
    Gen.frequency(
      3 -> Gen.choose(-4L, 4L),
      2 -> Gen.choose(Long.MinValue, Long.MaxValue),
      1 -> Gen.oneOf(0L, 1L, -1L, Long.MinValue, Long.MaxValue),
    )

  property("a value the constructor accepts survives from and value unchanged".tag(Property)):
    forAll(validValue) {
      case (identifier, text) =>
        (identifier.parse(text) ?= Right(text)).label(identifier.name)
    }

  property("surrounding whitespace is trimmed and nothing else is rewritten".tag(Property)):
    forAll(paddedValue) {
      case (identifier, padded, expected) =>
        (identifier.parse(padded) ?= Right(expected)).label(s"${identifier.name} from '$padded'")
    }

  property("parsing an accepted value a second time is a no-op".tag(Property)):
    forAll(anyStringType, PropertyBase.hostileText) { (identifier, raw) =>
      val once = identifier.parse(raw)
      (once.flatMap(identifier.parse) ?= once).label(s"${identifier.name} from '$raw'")
    }

  property("a blank value is rejected, and blamed on the type's own field".tag(Property)):
    forAll(anyStringType, PropertyBase.blank) { (identifier, text) =>
      identifier.parse(text) match
        case Left(error)  => (error.field ?= identifier.field).label(identifier.name)
        case Right(value) => Prop.falsified.label(s"${identifier.name} accepted the blank value '$value'")
    }

  property("no input, however hostile, produces a value that breaks the type's promise".tag(Property)):
    forAll(anyStringType, PropertyBase.hostileText) { (identifier, raw) =>
      identifier.parse(raw) match
        case Left(_)         => Prop.passed
        case Right(accepted) =>
          Prop
            .propBoolean(identifier.invariant(accepted))
            .label(s"${identifier.name} turned '$raw' into '$accepted'")
    }

  property("a rejection never echoes the value it rejected".tag(Property)):
    forAll(anyStringType, PropertyBase.marker, PropertyBase.hostileText) { (identifier, mark, raw) =>
      identifier.parse(s"$mark$raw$mark") match
        case Right(_)    => Prop.passed
        case Left(error) =>
          Prop
            .propBoolean(!error.message.contains(mark))
            .label(s"${identifier.name} said: ${error.message}")
    }

  property("a numeric identifier is accepted exactly when it reaches its minimum".tag(Property)):
    forAll(anyNumericType, anyLong) { (identifier, value) =>
      (identifier.parse(value).isRight ?= (value >= identifier.minimum))
        .label(s"${identifier.name} given $value")
    }

  property("an accepted numeric identifier keeps its value exactly".tag(Property)):
    forAll(anyNumericType, Gen.choose(1L, Long.MaxValue)) { (identifier, value) =>
      (identifier.parse(value) ?= Right(value)).label(identifier.name)
    }

  property("a rejected numeric identifier is blamed on its own field".tag(Property)):
    forAll(anyNumericType, Gen.choose(Long.MinValue, 0L)) { (identifier, value) =>
      identifier.parse(value) match
        case Left(error) => (error.field ?= identifier.field).label(s"${identifier.name} given $value")
        case Right(_)    =>
          Prop
            .propBoolean(value >= identifier.minimum)
            .label(s"${identifier.name} accepted $value below its minimum ${identifier.minimum}")
    }

  property("a commit id is case-insensitive and normalises to lowercase".tag(Property)):
    forAll(hexText) { hex =>
      val upper = hex.toUpperCase(Locale.ROOT)
      (CommitSha.from(upper).map(_.value) ?= Right(hex)) && (CommitSha.from(upper) ?= CommitSha.from(hex))
    }

  property("a label colour is the same value whether or not the caller writes the '#'".tag(Property)):
    forAll(colourText) { digits =>
      (LabelColor.from(s"#$digits") ?= LabelColor.from(digits)) &&
      (LabelColor.from(digits).map(_.hashed) ?= Right(s"#$digits"))
    }

  property("a base URI keeps its scheme and loses every trailing slash".tag(Property)):
    forAll(PropertyBase.plainSegment, Gen.oneOf("http://", "https://"), Gen.choose(0, 4)) { (host, scheme, slashes) =>
      val expected = s"$scheme$host"
      (BaseUri.from(expected + "/".repeat(slashes)).map(_.value) ?= Right(expected))
        .label(s"$expected with $slashes trailing slashes")
    }

  property("no input produces a base URI this client cannot speak".tag(Property)):
    forAll(PropertyBase.hostileText) { raw =>
      BaseUri.from(raw) match
        case Left(_)         => Prop.passed
        case Right(accepted) =>
          val value = accepted.value
          Prop
            .propBoolean(
              (value.startsWith("http://") || value.startsWith("https://")) &&
              !value.endsWith("/") &&
              !value.exists(_.isControl) &&
              isTrimmed(value)
            )
            .label(s"BaseUri turned '$raw' into '$value'")
    }
