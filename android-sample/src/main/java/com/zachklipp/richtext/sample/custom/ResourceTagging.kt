package com.zachklipp.richtext.sample.custom

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
 * URI scheme prefix for knowledge base citations.
 */
const val KB_URI_SCHEME: String = "kb://"

internal val RESOURCE_TAG_PATTERN = Regex("""<resource\s+([^>]+?)\s*/?>""")
internal val ATTRIBUTE_PATTERN = Regex("""(\w+)\s*=\s*"([^"]+)"""")

/**
 * Type of resource referenced by a `kb://` URI or `<resource …/>` tag.
 */
enum class ResourceType {
    EMAIL_THREAD,
    EMAIL,
    CALENDAR_EVENT,
    CALENDAR_SERIES,
    CITATION,
    FILE,
    DOCUMENT,
    UNKNOWN,
    ;

    companion object {
        fun fromString(value: String): ResourceType = when (value.lowercase()) {
            "email_thread" -> EMAIL_THREAD
            "email" -> EMAIL
            "calendar_event" -> CALENDAR_EVENT
            "calendar_series" -> CALENDAR_SERIES
            "citation" -> CITATION
            "file" -> FILE
            "document" -> DOCUMENT
            else -> UNKNOWN
        }
    }
}

/**
 * Identifies a single resource reference instance. [index] is 1-based within its [resourceType].
 */
data class ResourceTagInfo(
    val resourceType: ResourceType,
    val uri: String,
    val index: Int = 1,
)

private val DefaultBadgeBackgroundColor = Color(0xFF6B7280)
private val DefaultBadgeContentColor = Color.White

@Composable
fun ResourceBadge(
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
