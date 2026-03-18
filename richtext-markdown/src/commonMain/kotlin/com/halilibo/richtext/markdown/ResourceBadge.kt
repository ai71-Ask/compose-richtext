package com.halilibo.richtext.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Default badge colors - can be customized via LocalResourceTagRenderer
 */
private val DefaultBadgeBackgroundColor = Color(0xFF6B7280) // Gray-500
private val DefaultBadgeContentColor = Color.White

/**
 * A numbered badge component that displays inline in markdown content.
 * Used to indicate clickable resource references.
 *
 * @param index The 1-based index number to display (1, 2, 3...)
 * @param modifier Modifier for the badge
 * @param size Size of the badge (default 20dp for inline use)
 * @param backgroundColor Background color of the badge
 * @param contentColor Color of the index number
 * @param onClick Callback when the badge is clicked
 */
@Composable
public fun ResourceBadge(
    index: Int,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    backgroundColor: Color = DefaultBadgeBackgroundColor,
    contentColor: Color = DefaultBadgeContentColor,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(1.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = index.toString(),
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
