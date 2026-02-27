package com.halilibo.richtext.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

/**
 * URI scheme prefix for knowledge base citations.
 * Used to identify markdown links that should be rendered as citation resource tags.
 */
public const val KB_URI_SCHEME: String = "kb://"

/**
 * Enum representing the type of resource in a resource tag.
 */
public enum class ResourceType {
    EMAIL_THREAD,
    EMAIL,
    CALENDAR_EVENT,
    CALENDAR_SERIES,
    CITATION,
    FILE,
    DOCUMENT,
    UNKNOWN;

    public companion object {
        /**
         * Parse a string value to ResourceType enum.
         * Returns UNKNOWN if the string doesn't match any known type.
         */
        public fun fromString(value: String): ResourceType {
            return when (value.lowercase()) {
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
}

/**
 * Data class representing a resource tag from markdown content.
 *
 * @param resourceType The type of resource
 * @param uri The unique identifier for this resource
 * @param index The 1-based display index of this resource in the content (1, 2, 3...)
 */
public data class ResourceTagInfo(
    val resourceType: ResourceType,
    val uri: String,
    val index: Int = 0
)

/**
 * Callback interface for resource tag click events.
 */
public fun interface OnResourceTagClick {
    public fun onClick(info: ResourceTagInfo)
}

/**
 * Configuration for rendering resource tags inline.
 *
 * @param initialSize Function to calculate the initial size of the inline badge.
 * @param onResourceTagClick Callback when a resource tag is clicked.
 * @param content Composable content to render for each resource tag.
 */
public class ResourceTagRenderer(
    public val initialSize: (Density.() -> IntSize)? = null,
    public val onResourceTagClick: OnResourceTagClick? = null,
    public val content: @Composable (ResourceTagInfo, () -> Unit) -> Unit
)

/**
 * CompositionLocal for providing ResourceTagRenderer throughout the composition tree.
 * When null, resource tags will be rendered as plain text.
 */
public val LocalResourceTagRenderer: androidx.compose.runtime.ProvidableCompositionLocal<ResourceTagRenderer?> =
    staticCompositionLocalOf { null }

/**
 * CompositionLocal for providing URI to index mapping, grouped by ResourceType.
 * This allows resource tags to display their correct index number within their type group.
 * Uses SnapshotStateMap to support dynamic addition of URIs.
 */
public val LocalResourceTagIndices: androidx.compose.runtime.ProvidableCompositionLocal<SnapshotStateMap<ResourceType, SnapshotStateMap<String, Int>>> =
    staticCompositionLocalOf { SnapshotStateMap() }
