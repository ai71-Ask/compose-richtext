package com.halilibo.richtext.commonmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.halilibo.richtext.markdown.AstBlockNodeComposer
import com.halilibo.richtext.markdown.AstInlineNodeComposer
import com.halilibo.richtext.markdown.BasicMarkdown
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.ui.RichTextScope

/**
 * A composable that renders Markdown content according to Commonmark specification using RichText.
 *
 * @param content Markdown text. No restriction on length.
 * @param markdownParseOptions Options for the Markdown parser.
 * @param astBlockNodeComposer An interceptor to take control of composing any block type node's
 * rendering. Use it to render images, html text, tables with your own components.
 */
@Composable
public fun RichTextScope.Markdown(
  content: String,
  markdownParseOptions: CommonMarkdownParseOptions = CommonMarkdownParseOptions.Default,
  astBlockNodeComposer: AstBlockNodeComposer? = null,
  astInlineNodeComposer: AstInlineNodeComposer? = null,
  plugins: List<AstNodePlugin> = emptyList(),
) {
  val commonmarkAstNodeParser = remember(markdownParseOptions, plugins) {
    CommonmarkAstNodeParser(markdownParseOptions, plugins)
  }

  val astRootNode by produceState<AstNode?>(
    initialValue = null,
    key1 = commonmarkAstNodeParser,
    key2 = content
  ) {
    value = commonmarkAstNodeParser.parse(content)
  }

  astRootNode?.let {
    BasicMarkdown(
      astNode = it,
      astBlockNodeComposer = astBlockNodeComposer,
      astInlineNodeComposer = astInlineNodeComposer,
    )
  }
}

/**
 * A helper class that can convert any text content into an ASTNode tree and return its root.
 *
 * @param options Options for the Commonmark Markdown parser.
 * @param plugins Optional list of [AstNodePlugin]s consulted before the built-in commonmark-to-AST
 *   mapping. Use these to introduce custom node types (subclasses of
 *   [com.halilibo.richtext.markdown.node.AstCustomInlineNodeType] or
 *   [com.halilibo.richtext.markdown.node.AstCustomBlockNodeType]) without modifying the library.
 */
public expect class CommonmarkAstNodeParser(
  options: CommonMarkdownParseOptions = CommonMarkdownParseOptions.Default,
  plugins: List<AstNodePlugin> = emptyList(),
) {

  /**
   * Parse markdown content and return Abstract Syntax Tree(AST).
   *
   * @param text Markdown text to be parsed.
   */
  public fun parse(text: String): AstNode
}