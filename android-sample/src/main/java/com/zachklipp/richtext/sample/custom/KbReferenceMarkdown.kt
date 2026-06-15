package com.zachklipp.richtext.sample.custom

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.halilibo.richtext.commonmark.AstNodePlugin
import com.halilibo.richtext.markdown.AstInlineNodeComposer
import com.halilibo.richtext.markdown.node.AstCustomInlineNodeType
import com.halilibo.richtext.markdown.node.AstInlineNodeType
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.markdown.node.AstNodeType
import com.halilibo.richtext.markdown.node.AstText
import com.halilibo.richtext.ui.string.InlineContent
import com.halilibo.richtext.ui.string.RichTextString
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.Text

/**
 * AST node for a knowledge-base resource reference. Carries the parsed [resourceType] and
 * [uri]; [KbInlineComposer] is responsible for rendering it as a numbered badge.
 */
data class AstKbReference(
    val resourceType: ResourceType,
    val uri: String,
) : AstCustomInlineNodeType()

private fun parseResourceTagLiteral(literal: String): AstKbReference? {
    val tagMatch = RESOURCE_TAG_PATTERN.find(literal) ?: return null
    val attributes = ATTRIBUTE_PATTERN.findAll(tagMatch.groupValues[1])
        .associate { it.groupValues[1] to it.groupValues[2] }
    val type = attributes["type"] ?: return null
    val uri = attributes["uri"] ?: return null
    return AstKbReference(ResourceType.fromString(type), uri)
}

/**
 * Parser plugin that recognizes the three forms produced by the chat backend:
 *
 *  - Markdown link `[label](kb://uri)` → autolinker also produces these from bare `kb://uri`
 *  - Inline HTML `<resource type="..." uri="..." />`
 *  - HTML block `<resource type="..." uri="..." />` on its own line
 *
 * For autolinked `[kb://uri]` the surrounding `[/]` characters end up as siblings of the Link
 * node; this plugin strips them in-place on the commonmark tree before conversion sees them, so
 * the rendered output is just the badge.
 */
class KbReferencePlugin : AstNodePlugin {
    override fun convert(node: Any): AstNodeType? = when (node) {
        is Link -> {
            val dest = node.destination
            if (dest != null && dest.startsWith(KB_URI_SCHEME)) {
                AstKbReference(ResourceType.CITATION, dest)
            } else {
                null
            }
        }
        is Text -> {
            // When commonmark autolinks `[kb://x]`, the `[` and `]` become Text siblings of the
            // Link node. By the time the Link is processed, the preceding Text AstNode is already
            // created, so we must intercept Text nodes here — before conversion — to strip them.
            var literal = node.literal
            val prev = node.previous
            if (prev is Link && prev.destination?.startsWith(KB_URI_SCHEME) == true && literal.startsWith("]")) {
                literal = literal.drop(1)
            }
            val next = node.next
            if (next is Link && next.destination?.startsWith(KB_URI_SCHEME) == true && literal.endsWith("[")) {
                literal = literal.dropLast(1)
            }
            if (literal != node.literal) AstText(literal) else null
        }
        is HtmlInline -> parseResourceTagLiteral(node.literal)
        is HtmlBlock -> parseResourceTagLiteral(node.literal.trim())
        else -> null
    }
}

/**
 * Renders [AstKbReference] as an inline numbered badge. Indices are assigned per [ResourceType]
 * via [indices], 1-based within each type. [onClick] receives the resolved [ResourceTagInfo].
 */
class KbInlineComposer(
    private val indices: SnapshotStateMap<ResourceType, SnapshotStateMap<String, Int>>,
    private val onClick: ((ResourceTagInfo) -> Unit)? = null,
    private val backgroundColor: Color? = null,
    private val contentColor: Color? = null,
) : AstInlineNodeComposer {

    override fun predicate(astInlineNodeType: AstInlineNodeType): Boolean =
        astInlineNodeType is AstKbReference

    override fun appendInline(builder: RichTextString.Builder, astNode: AstNode) {
        val ref = astNode.type as AstKbReference
        builder.appendInlineContent(
            content = InlineContent(
                initialSize = { IntSize(24.dp.roundToPx(), 20.dp.roundToPx()) },
            ) {
                val typeIndices = indices.getOrPut(ref.resourceType) { SnapshotStateMap() }
                val index = typeIndices[ref.uri]
                    ?: (typeIndices.size + 1).also { typeIndices[ref.uri] = it }
                val info = ResourceTagInfo(ref.resourceType, ref.uri, index)
                ResourceBadge(
                    index = index,
                    backgroundColor = backgroundColor ?: MaterialTheme.colorScheme.secondary,
                    contentColor = contentColor ?: MaterialTheme.colorScheme.primary,
                    onClick = { onClick?.invoke(info) },
                )
            },
        )
    }
}
