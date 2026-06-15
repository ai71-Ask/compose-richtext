package com.zachklipp.richtext.sample.custom

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.halilibo.richtext.commonmark.CommonMarkdownParseOptions
import com.halilibo.richtext.commonmark.CommonmarkAstNodeParser
import com.halilibo.richtext.markdown.AstBlockNodeComposer
import com.halilibo.richtext.markdown.BasicMarkdown
import com.halilibo.richtext.markdown.node.AstBlockNodeType
import com.halilibo.richtext.markdown.node.AstFootDefinition
import com.halilibo.richtext.markdown.node.AstFootReferenceDefinition
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.ui.RichTextScope
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.merge
import com.halilibo.richtext.ui.resolveDefaults
import com.halilibo.richtext.ui.string.RichTextStringStyle

private val KB_URL_PATTERN = Regex("""kb://[^\s)\]<>"']+""")

/**
 * Pre-scan the raw Markdown so badge indices are stable in source order — the renderer assigns
 * indices lazily as nodes compose, which can drift if a later citation composes before an
 * earlier one.
 */
private fun extractResourceIndices(content: String): Map<ResourceType, Map<String, Int>> {
    val indicesByType = mutableMapOf<ResourceType, MutableMap<String, Int>>()
    val countersByType = mutableMapOf<ResourceType, Int>()

    val urlIndices = linkedMapOf<String, Int>()
    KB_URL_PATTERN.findAll(content).forEach { match ->
        val url = match.value
        if (url !in urlIndices) urlIndices[url] = urlIndices.size + 1
    }
    indicesByType[ResourceType.CITATION] = urlIndices

    RESOURCE_TAG_PATTERN.findAll(content).forEach { tagMatch ->
        val attributes = ATTRIBUTE_PATTERN.findAll(tagMatch.groupValues[1])
            .associate { it.groupValues[1] to it.groupValues[2] }
        val uri = attributes["uri"]
        val typeString = attributes["type"]
        if (uri != null && typeString != null) {
            val resourceType = ResourceType.fromString(typeString)
            val typeMap = indicesByType.getOrPut(resourceType) { mutableMapOf() }
            if (!typeMap.containsKey(uri)) {
                val nextIndex = countersByType.getOrPut(resourceType) { 1 }
                typeMap[uri] = nextIndex
                countersByType[resourceType] = nextIndex + 1
            }
        }
    }

    return indicesByType
}

@Composable
fun RichMarkdownText(
    modifier: Modifier = Modifier,
    content: String,
    style: TextStyle = LocalTextStyle.current,
    fadeEffect: Boolean = false,
    onResourceClick: ((ResourceTagInfo) -> Unit)? = null,
) {
    val richTextStyle = remember(style) {
        RichTextStyle().resolveDefaults().merge(
            RichTextStyle(
                stringStyle = RichTextStringStyle(
                    linkStyle = TextLinkStyles(
                        style = style.toSpanStyle().copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ),
        )
    }

    val originalUriHandler = LocalUriHandler.current
    val customUriHandler = remember(originalUriHandler, onResourceClick) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (onResourceClick != null && uri.startsWith(KB_URI_SCHEME)) {
                    onResourceClick(ResourceTagInfo(ResourceType.CITATION, uri))
                } else {
                    originalUriHandler.openUri(uri)
                }
            }
        }
    }

    val resourceIndices = remember(content) {
        SnapshotStateMap<ResourceType, SnapshotStateMap<String, Int>>().also { groupedMap ->
            extractResourceIndices(content).forEach { (resourceType, uriMap) ->
                val typeMap = SnapshotStateMap<String, Int>()
                typeMap.putAll(uriMap)
                groupedMap[resourceType] = typeMap
            }
        }
    }

    val secondary = MaterialTheme.colorScheme.secondary
    val primary = MaterialTheme.colorScheme.primary
    val inlineComposer = remember(onResourceClick, secondary, primary) {
        KbInlineComposer(
            indices = resourceIndices,
            onClick = onResourceClick,
            backgroundColor = secondary,
            contentColor = primary,
        )
    }

    val astBlockNodeComposer = remember {
        CustomBlockNodeComposer()
    }

    CompositionLocalProvider(
        LocalContentColor provides style.color,
        LocalTextStyle provides style,
        LocalUriHandler provides customUriHandler,
    ) {
        RichText(
            modifier = modifier,
            style = richTextStyle,
        ) {
            val parser = remember(fadeEffect) {
                CommonmarkAstNodeParser(
                    options = CommonMarkdownParseOptions(
                        autolink = true,
                        fadeEffect = fadeEffect,
                    ),
                    plugins = listOf(KbReferencePlugin()),
                )
            }
            val astNode = remember(parser, content) {
                parser.parse(content.trimIndent())
            }

            BasicMarkdown(
                astNode = astNode,
                astBlockNodeComposer = astBlockNodeComposer,
                astInlineNodeComposer = inlineComposer,
            )
        }
    }
}

class CustomBlockNodeComposer(
    val onClick: ((String) -> Unit)? = null,
) : AstBlockNodeComposer {

    override fun predicate(astBlockNodeType: AstBlockNodeType): Boolean {
        return astBlockNodeType is AstFootDefinition || astBlockNodeType is AstFootReferenceDefinition
    }

    @Composable
    override fun RichTextScope.Compose(
        astNode: AstNode,
        visitChildren: @Composable (AstNode) -> Unit,
    ) {
        when (val type = astNode.type) {
            is AstFootReferenceDefinition -> {
                TextButton(onClick = { onClick?.invoke(type.label) }) {
                    Text(text = type.label, color = Color.Red)
                }
                visitChildren(astNode)
            }
            is AstFootDefinition -> {
                TextButton(onClick = { onClick?.invoke(type.label) }) {
                    Text(text = type.label, color = Color.Red)
                    visitChildren(astNode)
                }
            }
            else -> visitChildren(astNode)
        }
    }
}
