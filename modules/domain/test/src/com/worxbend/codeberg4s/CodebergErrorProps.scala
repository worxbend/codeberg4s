package com.worxbend.codeberg4s

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.forAllNoShrink

/** What `CodebergError.describe` promises about its own size.
  *
  * `describe` is what a caller logs and what `CodebergException` puts in its message, so its length is not cosmetic: a
  * rendering that grows with the response turns one failed call into a payload dump in someone's log aggregator, which
  * is the whole reason [[CodebergError.MaxSnippetLength]] exists. The type states the contract itself: *"Every
  * free-form fragment is truncated at [[CodebergError.MaxSnippetLength]], which bounds the output of a single level;
  * nested [[CodebergError.RetriesExhausted]] adds one bounded level per nesting step."*
  *
  * Each property below drives '''one''' position of one error case with oversized text, so a failure names the fragment
  * that is not in fact truncated rather than merely reporting that the rendering was long. The ceiling is eight times
  * `MaxSnippetLength` and the oversized text is at least ten times it, so only a genuinely unbounded fragment can fail:
  * the fixed prose and a handful of truncated fragments cannot add up to it, and a truncated fragment cannot exceed it.
  *
  * Shrinking is switched off throughout. A counterexample here is a five-kilobyte string whose content is irrelevant —
  * its '''length''' is the point — and letting ScalaCheck shrink one character at a time costs minutes and tells nobody
  * anything.
  *
  * '''Five of these properties fail today, and they are meant to.''' They are not weakened to green, because each one
  * is a fragment the type's own Scaladoc says is truncated and which is not:
  *
  *   - `TransportCause`'s `detail`, embedded through `cause.describe` with no `bound(…)` around it. The detail comes
  *     from the underlying exception message the transport adapter caught.
  *   - `JsonPath.render`, whose segments `Json.pathOf` recovers from the '''response's own JSON keys''', so the size of
  *     this fragment is chosen by the server.
  *   - `CallContext.requestId`, taken verbatim from the instance's `x-request-id` response header — again, server
  *     chosen.
  *   - `CallContext.uri`, which grows with a caller's own path segments and query values; `Redaction` masks credentials
  *     in it but never shortens it.
  *   - `ApiErrorBody.errors`, where each element is truncated but the list is not, so the aggregate is unbounded.
  *
  * The bounded fragments — the body snippet, the server's `message`, a validation reason — do pass, which is what makes
  * the failures a gap in `describe` rather than a misreading of the contract. Fixing them is a change to
  * `CodebergError`, which this lane does not own; the properties stay red until it happens.
  */
final class CodebergErrorProps extends PropertyBase:

  /** How much rendering the documented contract allows for one level, generously rounded up. */
  private val Ceiling: Int = CodebergError.MaxSnippetLength * 8

  /** Text long enough that leaving it untruncated cannot be mistaken for the fixed prose around it. */
  private val oversized: Gen[String] =
    Gen
      .choose(CodebergError.MaxSnippetLength * 10, CodebergError.MaxSnippetLength * 20)
      .flatMap(length => Gen.listOfN(length, Gen.oneOf('a' to 'z')).map(_.mkString))

  private def contextWith(uri: String, requestId: Option[String]): CallContext =
    CallContext("issues.list", HttpMethod.Get, uri, requestId, 12L)

  private val ordinaryContext: CallContext =
    contextWith("https://codeberg.org/api/v1/repos/owner/name/issues", None)

  private def within(name: String, error: CodebergError): Prop =
    val rendered = error.describe
    Prop
      .propBoolean(rendered.length <= Ceiling)
      .label(s"$name rendered ${rendered.length} characters against a ceiling of $Ceiling")

  property("the body snippet and the decoder's own reason are truncated".tag(Property)):
    forAllNoShrink(oversized, oversized) { (snippet, reason) =>
      within("DecodingFailed(snippet)", CodebergError.DecodingFailed(ordinaryContext, snippet, JsonPath.Root, "why")) &&
      within("DecodingFailed(cause)", CodebergError.DecodingFailed(ordinaryContext, "{", JsonPath.Root, reason))
    }

  property("a server-supplied message and a validation reason are truncated".tag(Property)):
    forAllNoShrink(oversized) { text =>
      within("Api(body.message)", CodebergError.Api(ordinaryContext, 500, ApiErrorBody(Some(text), None, Nil), None)) &&
      within("Validation(message)", CodebergError.Validation(ValidationError("owner", text))) &&
      within("Transport(cause detail)", CodebergError.Transport(ordinaryContext, TransportCause.Unknown(text)))
    }

  property("the JSON path a decoding failure reports is truncated".tag(Property)):
    forAllNoShrink(oversized) { key =>
      within(
        "DecodingFailed(path)",
        CodebergError.DecodingFailed(ordinaryContext, "{", JsonPath.of(key), "unexpected value"),
      )
    }

  property("the redacted URI a failure carries is truncated".tag(Property)):
    forAllNoShrink(oversized) { uri =>
      within("Transport(ctx.uri)", CodebergError.Transport(contextWith(uri, None), TransportCause.Timeout("read")))
    }

  property("the correlation id the instance supplied is truncated".tag(Property)):
    forAllNoShrink(oversized) { requestId =>
      within(
        "Transport(ctx.requestId)",
        CodebergError.Transport(contextWith("https://codeberg.org/api/v1", Some(requestId)), TransportCause.Dns("x")),
      )
    }

  property("a long list of per-field errors is truncated".tag(Property)):
    forAllNoShrink(Gen.choose(500, 2000)) { count =>
      within(
        "Api(body.errors)",
        CodebergError.Api(ordinaryContext, 422, ApiErrorBody(None, None, List.fill(count)("field is required")), None),
      )
    }

  property("nesting a retry failure adds one bounded level, not an unbounded one".tag(Property)):
    forAllNoShrink(Gen.choose(1, 6), oversized) { (depth, text) =>
      val innermost: CodebergError = CodebergError.Transport(ordinaryContext, TransportCause.Unknown(text))
      val nested                   = (1 to depth).foldLeft(innermost) { (inner, level) =>
        CodebergError.RetriesExhausted(ordinaryContext, level + 1, inner)
      }

      Prop
        .propBoolean(nested.describe.length <= Ceiling * (depth + 1))
        .label(s"$depth levels rendered ${nested.describe.length} characters")
    }

  property("describe never loses the status a caller branches on".tag(Property)):
    forAllNoShrink(Gen.choose(400, 599), oversized) { (status, text) =>
      val rendered = CodebergError.Api(ordinaryContext, status, ApiErrorBody(Some(text), None, Nil), None).describe

      Prop.propBoolean(rendered.contains(status.toString)).label(s"$status is missing from the rendering")
    }
