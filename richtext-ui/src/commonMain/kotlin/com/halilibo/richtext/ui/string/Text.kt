package com.halilibo.richtext.ui.string

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
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
import kotlin.math.pow

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
  val annotated = remember(text, style, contentColor) {
    val resolvedStyle = (style ?: RichTextStringStyle.Default).resolveDefaults()
    if (fadeOutEffect) {
        applyAnimatedFadeEffect(
            text.toAnnotatedString(resolvedStyle, contentColor), contentColor, fadeLength = 20, fadeMultiplier = 0.8f
        )
    } else {
        text.toAnnotatedString(resolvedStyle, contentColor)
    }
  }

  val inlineContents = remember(text) { text.getInlineContents() }

  if (inlineContents.isEmpty()) {
    Text(
      text = annotated,
      onTextLayout = onTextLayout,
      softWrap = softWrap,
      overflow = overflow,
      maxLines = maxLines
    )
  } else {
    // expensive constraints reading path
    BoxWithConstraints(modifier = modifier) {
      val inlineTextContents = manageInlineTextContents(
        inlineContents = inlineContents,
        textConstraints = constraints
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

private fun applyAnimatedFadeEffect(
    content: AnnotatedString,
    textColor: Color,
    fadeLength: Int,
    fadeMultiplier: Float
): AnnotatedString {
    val text = content.text
    if (text.isEmpty()) return content

    // Find the last non-whitespace character
    val trimmedLength = text.trimEnd().length
    if (trimmedLength == 0) return content

    val actualFadeLength = minOf(trimmedLength, fadeLength)
    val fadeStartIndex = trimmedLength - actualFadeLength

    return buildAnnotatedString {
        // Copy the original text and styles up to fade start
        append(content.subSequence(0, fadeStartIndex))

        // Apply fade effect to each character individually
        for (i in 0 until actualFadeLength) {
            val charIndex = fadeStartIndex + i
            val baseFadeAlpha = 0.89f.pow(i.toFloat())

            // Calculate final alpha: interpolate between baseFadeAlpha and 1.0
            val finalAlpha = baseFadeAlpha + (1.0f - baseFadeAlpha) * (1.0f - fadeMultiplier)
            val fadeColor = textColor.copy(alpha = finalAlpha)

            withStyle(SpanStyle(color = fadeColor)) {
                append(text[charIndex])
            }
        }

        // Append any remaining text after trimmed content (whitespace)
        if (trimmedLength < text.length) {
            append(text.substring(trimmedLength))
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
