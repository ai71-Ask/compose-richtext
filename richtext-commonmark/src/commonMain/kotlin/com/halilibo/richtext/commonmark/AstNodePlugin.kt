package com.halilibo.richtext.commonmark

import com.halilibo.richtext.markdown.node.AstNodeType

/**
 * Parser plugin hook. Implementations inspect a parsed commonmark node and may produce a custom
 * [AstNodeType] (typically a subclass of
 * [com.halilibo.richtext.markdown.node.AstCustomInlineNodeType] or
 * [com.halilibo.richtext.markdown.node.AstCustomBlockNodeType]).
 *
 * Plugins are consulted before the built-in commonmark-to-AST mapping; the first plugin to return
 * a non-null value wins. Returning null falls through to the next plugin and finally to the
 * library's default mapping.
 *
 * The [convert] function takes the platform's commonmark node type. On JVM/Android targets this
 * is `org.commonmark.node.Node`; the parameter is typed as [Any] in common code so this interface
 * can sit in commonMain — implementations should live in jvmAndroidMain and cast to `Node`.
 */
public fun interface AstNodePlugin {
  public fun convert(node: Any): AstNodeType?
}
