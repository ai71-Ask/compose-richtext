# Fork Notes

This is an Ask71-internal fork of [`halilozercan/compose-richtext`](https://github.com/halilozercan/compose-richtext). The
`upstream` branch tracks the original; `main` carries our local additions.

The notes below describe **what** has been added and **why**, so a future maintainer (or future-you reviewing a sync from
upstream) doesn't have to do `git log` archaeology to understand the divergence.

## Local additions

### 1. Plugin + composer system for custom AST nodes

Upstream's `AstNodeType` hierarchy is `sealed`, so consumers cannot introduce new node types without modifying the
library. We've opened two extension points without de-sealing the hierarchy:

| Class | Location (commonMain) | Purpose |
| --- | --- | --- |
| `AstCustomInlineNodeType` | `richtext-markdown/.../node/AstNodeType.kt` | Open base for consumer inline nodes |
| `AstCustomBlockNodeType` | same | Open base for consumer block nodes |
| `AstNodePlugin` (fun-iface) | `richtext-commonmark/.../AstNodePlugin.kt` | Parse-time hook: commonmark `Node` → `AstNodeType` |
| `AstInlineNodeComposer` | `richtext-markdown/.../BasicMarkdown.kt` | Render-time hook: mirror of `AstBlockNodeComposer` for inline |

End-to-end shape:

1. **Parse**: `CommonmarkAstNodeParser(options, plugins)` consults each plugin **before** the built-in
   commonmark-to-AST mapping; first non-null wins. Plugins return any `AstNodeType` (typically a subclass of
   `AstCustomInlineNodeType`). The plugin parameter is `Any` in commonMain because `org.commonmark.node.Node` is
   JVM-only — implementations live in jvmAndroidMain and cast.
2. **Tree shape**: `AstCustomInlineNodeType` and `AstCustomBlockNodeType` are treated as **leaves** during conversion —
   children of the originating commonmark node are not recursed into. The renderer owns whatever traversal it needs.
3. **Render**:
   - At inline level (inside `MarkdownRichText`'s rich-text-string traversal): `AstInlineNodeComposer.appendInline` is
     called with a `RichTextString.Builder`, typically appending an `InlineContent` block.
   - At block level (when a block-level commonmark node like `HtmlBlock` is mapped to a custom inline type, e.g.
     `<resource …/>` on its own line): `BasicMarkdown` falls back to wrapping the inline composer's output in a
     synthetic `Text` so the badge still appears.
   - Block-shaped custom types are routed through `AstBlockNodeComposer` exactly like the existing footnote handling.

Consumers wire all three (`plugins`, `astBlockNodeComposer`, `astInlineNodeComposer`) when calling `BasicMarkdown` /
`Markdown`.

## Consumer dependency note

Because `AstNodePlugin.convert(node: Any)` is implemented in JVM-only consumer code that casts to
`org.commonmark.node.Node`, the consumer module needs a direct dependency on commonmark. Already added to
`feature/chat/build.gradle.kts` as `implementation(libs.commonmark.core)` (version catalog: `commonmark = "0.26.0"`).

## Syncing with upstream

```bash
git fetch upstream
git checkout upstream
git pull upstream main
git checkout main
git merge upstream
```

When merging, expect conflicts in:
- `richtext-markdown/.../node/AstNodeType.kt` (custom node base classes)
- `richtext-markdown/.../BasicMarkdown.kt` (inline composer wiring)
- `richtext-markdown/.../MarkdownRichText.kt` (custom inline node arm)
- `richtext-markdown/.../TraverseUtils.kt` (`isRichTextTerminal` includes `AstCustomInlineNodeType`)
- `richtext-commonmark/.../Markdown.kt` (plugins parameter on expect class)
- `richtext-commonmark/.../AstNodeConvert.kt` (plugin loop in `convertNodeType`)
