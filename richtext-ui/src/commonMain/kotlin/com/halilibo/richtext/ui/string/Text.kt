package com.halilibo.richtext.ui.string

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.halilibo.richtext.ui.RichTextScope
import com.halilibo.richtext.ui.Text
import com.halilibo.richtext.ui.currentContentColor
import com.halilibo.richtext.ui.currentRichTextStyle
import com.halilibo.richtext.ui.string.RichTextString.Format
import kotlin.math.ceil
import kotlin.math.floor

// Paced by distance, clamped to a duration band — NOT a fixed chars/sec rate. A fixed rate (what
// this used to be) has no way to keep up with a fast tick: if the model streams faster than the
// rate allows, the gap between revealedLength and the true length grows every tick until it trips
// the big-jump snap below, which shows up as "large chunks just appear, no fade at all". Clamping
// duration instead means a burst automatically sweeps faster (higher effective chars/sec) the
// bigger it is, so it always finishes inside this band regardless of how much text arrived.
//
// This rate only actually determines the duration for delta roughly in [15, 33] characters: below
// 15, MIN_REVEAL_DURATION_MS floors it (15 * 12ms == 180ms == the floor); above ~33
// (33.33 * 12ms == 400ms == the ceiling), MAX_REVEAL_DURATION_MS caps it instead. Outside that
// narrow band this constant has no effect on the actual duration at all.
private const val REVEAL_MS_PER_CHARACTER = 12f
private const val MIN_REVEAL_DURATION_MS = 180
private const val MAX_REVEAL_DURATION_MS = 400

// Backstop for genuinely pathological jumps only (e.g. attach/replay dumping a large backlog at
// once). The old, much lower threshold assumed a fixed chars/sec rate, where a huge delta meant a
// slow multi-second crawl through content the user is just catching up on — that risk is gone now
// that duration is clamped to the band above: a bigger delta always sweeps FASTER, never slower.
// A coalescing upstream buffer feeding chunks every ~100ms can easily accumulate a backlog in the
// hundreds of characters under fast streaming alone, so this only needs to catch dumps of
// thousands of characters, not ordinary fast streaming.
private const val MAX_ANIMATED_DELTA_CHARS = 1500f

// The fade window (see [wavefrontAlpha]) is computed per sweep so a character's own fade lasts
// roughly TARGET_CHAR_FADE_MS of wall-clock time regardless of how fast the stream is moving:
// charsPerMs * TARGET_CHAR_FADE_MS characters need to be "in flight" at once to keep that constant.
// Clamped so a very slow trickle doesn't shrink the window to an imperceptible sliver, and a huge
// burst doesn't stretch it across a third of the text.
private const val TARGET_CHAR_FADE_MS = 120f
private const val MIN_FADE_WINDOW_CHARS = 8f
private const val MAX_FADE_WINDOW_CHARS = 120f

// Floor for the fade-window ramp: without it, a settled character sitting right at the wavefront
// (i.e. the very end of the currently revealed text) lands at alpha ~= 1/fadeWindow, which can be
// as low as ~1% opacity — during backend think-time between chunks, the visible tail of the text
// would just sit there looking nearly invisible instead of "recently revealed."
private const val WAVEFRONT_MIN_ALPHA = 0.3f

/**
 * Renders [content] with a soft trailing gradient behind the animated reveal wavefront: alpha
 * ramps from [WAVEFRONT_MIN_ALPHA] (just crossed the wavefront) to `1f` (settled, distance >=
 * [fadeWindowChars] behind it) with distance behind the wavefront, and characters not yet reached
 * are `0f`. Used both for plain characters here (see [applyAnimatedFadeEffect]) and for fading an
 * inline-content badge's own composable (see the `Text` composable below) — the badge's start
 * offset stands in for a character index.
 */
private fun wavefrontAlpha(
    index: Int,
    hiddenFrom: Int,
    visibleUpTo: Float,
    fadeWindowChars: Float,
): Float {
    if (index >= hiddenFrom) return 0f
    val distanceBehindWavefront = visibleUpTo - index
    if (distanceBehindWavefront >= fadeWindowChars) return 1f
    val rampProgress = (distanceBehindWavefront / fadeWindowChars).coerceIn(0f, 1f)
    return (WAVEFRONT_MIN_ALPHA + (1f - WAVEFRONT_MIN_ALPHA) * rampProgress).coerceIn(WAVEFRONT_MIN_ALPHA, 1f)
}

/**
 * Renders a [RichTextString] as created with [richTextString].
 */
@Suppress("UnusedBoxWithConstraintsScope")
@Composable
public fun RichTextScope.Text(
  text: RichTextString,
  modifier: Modifier = Modifier,
  onTextLayout: (TextLayoutResult) -> Unit = {},
  softWrap: Boolean = true,
  fadeOutEffect: Boolean = false,
  overflow: TextOverflow = TextOverflow.Clip,
  maxLines: Int = Int.MAX_VALUE
) {
  val style = currentRichTextStyle.stringStyle
  val contentColor = currentContentColor
  val resolvedAnnotated = remember(text, style, contentColor) {
    val resolvedStyle = (style ?: RichTextStringStyle.Default).resolveDefaults()
    text.toAnnotatedString(resolvedStyle, contentColor)
  }

  // Chases the text's growing length rather than recomputing a static alpha snapshot on every
  // recomposition (the previous approach) — animateTo() on an Animatable that's already mid-flight
  // interrupts and smoothly retargets from its current value, so a fresh chunk arriving before the
  // previous one finished revealing eases the reveal onward instead of restarting it. Only ever
  // touches alpha on the already-built AnnotatedString, so it never re-triggers the markdown
  // reparse upstream in RichMarkdownText.
  val revealedLength = remember { Animatable(resolvedAnnotated.text.length.toFloat()) }

  // Width, in characters, of the fade window for the *current* sweep (see [wavefrontAlpha]) — a
  // property of the sweep, not of the frame, so it's computed once per LaunchedEffect run (below)
  // and held here rather than recomputed from instantaneous per-frame values. 0 disables the ramp
  // (see the snap branch below).
  val fadeWindowChars = remember { mutableFloatStateOf(0f) }

  LaunchedEffect(resolvedAnnotated.text, fadeOutEffect) {
    val target = resolvedAnnotated.text.length.toFloat()
    val delta = target - revealedLength.value
    if (!fadeOutEffect || delta <= 0f || delta > MAX_ANIMATED_DELTA_CHARS) {
      // Not streaming, no growth, or a big one-shot jump (e.g. attach/replay backlog) — zero the
      // window too, so the snap lands at full opacity instantly instead of leaving the trailing
      // characters at a lingering partial alpha.
      fadeWindowChars.floatValue = 0f
      revealedLength.snapTo(target)
    } else {
      val durationMs = (delta * REVEAL_MS_PER_CHARACTER).toInt()
        .coerceIn(MIN_REVEAL_DURATION_MS, MAX_REVEAL_DURATION_MS)
      val charsPerMs = delta / durationMs
      fadeWindowChars.floatValue = (charsPerMs * TARGET_CHAR_FADE_MS)
        .coerceIn(MIN_FADE_WINDOW_CHARS, MAX_FADE_WINDOW_CHARS)
      revealedLength.animateTo(target, animationSpec = tween(durationMs))
    }
  }

  // Link character ranges, computed once per resolvedAnnotated (not re-queried on every animation
  // frame) — see [applyAnimatedFadeEffect]. Inline content (badges) doesn't need an equivalent
  // structure there any more; its own composable is faded directly below instead.
  val linkPositions = remember(resolvedAnnotated) { findLinkPositions(resolvedAnnotated) }

  val annotated = if (fadeOutEffect) {
    applyAnimatedFadeEffect(resolvedAnnotated, contentColor, revealedLength.value, fadeWindowChars.floatValue, linkPositions)
  } else {
    resolvedAnnotated
  }

  val inlineContents = remember(text) { text.getInlineContents() }

  // Start offset of each inline-content placeholder, keyed by tag — computed once per
  // resolvedAnnotated. Drives each badge's own graphicsLayer alpha (below) as the wavefront reaches
  // it. Previously the placeholder was replaced with a plain space while hidden, collapsing the
  // badge's reserved width until the wavefront passed and then snapping it back, visibly reflowing
  // the line; keeping the placeholder intact (see applyAnimatedFadeEffect) reserves its width from
  // frame one, so only its paint fades.
  val inlineContentOffsets = remember(resolvedAnnotated) {
    resolvedAnnotated.getStringAnnotations(INLINE_CONTENT_MARKER_TAG, 0, resolvedAnnotated.text.length)
      .associate { it.item to it.start }
  }

  val inlineContentAlphas = if (fadeOutEffect && inlineContentOffsets.isNotEmpty()) {
    val trimmedLength = resolvedAnnotated.text.trimEnd().length
    val visibleUpTo = revealedLength.value.coerceIn(0f, trimmedLength.toFloat())
    val hiddenFrom = floor(visibleUpTo).toInt().coerceIn(0, trimmedLength)
    inlineContentOffsets.mapValues { (_, start) ->
      wavefrontAlpha(start, hiddenFrom, visibleUpTo, fadeWindowChars.floatValue)
    }
  } else {
    emptyMap()
  }

  if (inlineContents.isEmpty()) {
    Text(
      text = annotated,
      onTextLayout = onTextLayout,
      softWrap = softWrap,
      overflow = overflow,
      maxLines = maxLines,
      modifier = modifier,
    )
  } else {
    // expensive constraints reading path
    BoxWithConstraints(modifier = modifier) {
      val inlineTextContents = manageInlineTextContents(
        inlineContents = inlineContents,
        textConstraints = constraints,
        inlineContentAlphas = inlineContentAlphas,
      )

      Text(
        text = annotated,
        onTextLayout = onTextLayout,
        inlineContent = inlineTextContents,
        softWrap = softWrap,
        overflow = overflow,
        maxLines = maxLines,
      )
    }
  }
}

/**
 * Link character ranges within a resolved [AnnotatedString], gathered once per content (see the
 * `remember` call site in the `Text` composable above) rather than re-queried on every animation
 * frame by [applyAnimatedFadeEffect]. [isLinkAt] gives O(1) point lookup for the fade-window loop,
 * which is bounded to a small number of characters ([MAX_FADE_WINDOW_CHARS]); [ranges] is walked
 * directly by the hidden-region run-based append so that stripping a link's annotation costs
 * O(links) rather than O(hidden characters).
 */
private class LinkPositions(
    val isLinkAt: BooleanArray,
    val ranges: List<AnnotatedString.Range<LinkAnnotation>>,
)

private fun findLinkPositions(content: AnnotatedString): LinkPositions {
    val length = content.text.length
    val isLinkAt = BooleanArray(length)
    val ranges = content.getLinkAnnotations(0, length).sortedBy { it.start }
    ranges.forEach { range ->
        for (i in range.start until range.end) isLinkAt[i] = true
    }
    return LinkPositions(isLinkAt, ranges)
}

/**
 * Renders [content] with a soft trailing gradient behind the animated [revealedLength] wavefront:
 * settled text is opaque, the last [fadeWindowChars] ramp via [wavefrontAlpha], and unrevealed text
 * is hidden. Recomputed every animation frame, which is what makes a character's fade read as a
 * transition rather than a one-frame pop.
 *
 * Links can't be faded via [SpanStyle] alpha — [RichTextString.Format.Link] resolves its own paint
 * independently of it — so a link still ahead of the settled prefix has its annotation stripped and
 * falls back to a plain, alpha-following character; it regains its link styling once the wavefront
 * passes it (`< windowStart`, via the bulk [AnnotatedString.subSequence] copy, which is what
 * preserves the annotation). Inline content needs no such stripping: it's always kept intact via bulk
 * `subSequence`, since its own composable is faded separately via `graphicsLayer` (see the `Text`
 * composable above). Every other character is likewise copied via `subSequence`, not raw `String`
 * slicing, so incidental formatting (bold/italic) survives the fade window.
 *
 * The hidden region (beyond the wavefront) is built run-based against [LinkPositions.ranges] instead
 * of character-by-character — a bulk `subSequence` per plain gap, one stripped append per overlapping
 * link — so it costs O(links) rather than O(hidden characters), since this region can be the entire
 * unstreamed backlog. Only the fade window itself is a per-character loop, bounded to
 * [MAX_FADE_WINDOW_CHARS].
 */
private fun applyAnimatedFadeEffect(
    content: AnnotatedString,
    textColor: Color,
    revealedLength: Float,
    fadeWindowChars: Float,
    linkPositions: LinkPositions,
): AnnotatedString {
    val text = content.text
    if (text.isEmpty()) return content

    // Find the last non-whitespace character
    val trimmedLength = text.trimEnd().length
    if (trimmedLength == 0) return content

    val visibleUpTo = revealedLength.coerceIn(0f, trimmedLength.toFloat())
    val hiddenFrom = floor(visibleUpTo).toInt().coerceIn(0, trimmedLength)
    val windowStart = (hiddenFrom - ceil(fadeWindowChars).toInt()).coerceAtLeast(0)

    return buildAnnotatedString {
        // Settled text, well behind the wavefront — bulk copy (preserves links and inline content),
        // no per-character cost
        append(content.subSequence(0, windowStart))

        // Soft trailing window: alpha ramps from WAVEFRONT_MIN_ALPHA (just crossed) to 1 (settled)
        // with distance behind the wavefront
        for (i in windowStart until hiddenFrom) {
            val alpha = wavefrontAlpha(i, hiddenFrom, visibleUpTo, fadeWindowChars)
            withStyle(SpanStyle(color = textColor.copy(alpha = alpha))) {
                if (linkPositions.isLinkAt[i]) append(text[i].toString()) else append(content.subSequence(i, i + 1))
            }
        }

        // Not yet revealed — hidden until the wavefront reaches it. Walked run-based against the
        // (precomputed) link ranges instead of per character: each plain gap is one bulk append
        // under a single shared alpha-0 span, and only links overlapping this region need their
        // annotation stripped.
        if (hiddenFrom < trimmedLength) {
            withStyle(SpanStyle(color = textColor.copy(alpha = 0f))) {
                var cursor = hiddenFrom
                for (range in linkPositions.ranges) {
                    if (range.end <= cursor) continue
                    if (range.start >= trimmedLength) break
                    val start = range.start.coerceAtLeast(cursor)
                    val end = range.end.coerceAtMost(trimmedLength)
                    if (start > cursor) append(content.subSequence(cursor, start))
                    if (end > start) append(text.substring(start, end))
                    cursor = end
                }
                if (cursor < trimmedLength) append(content.subSequence(cursor, trimmedLength))
            }
        }

        // Append any remaining text after trimmed content (whitespace)
        if (trimmedLength < text.length) {
            append(content.subSequence(trimmedLength, text.length))
        }
    }
}

private fun AnnotatedString.getConsumableAnnotations(textFormatObjects: Map<String, Any>, offset: Int): Sequence<Format.Link> =
  getStringAnnotations(Format.FormatAnnotationScope, offset, offset)
    .asSequence()
    .mapNotNull {
      Format.findTag(
        it.item,
        textFormatObjects
      ) as? Format.Link
    }
