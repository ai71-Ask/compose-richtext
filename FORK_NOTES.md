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

### 2. Streaming reveal applied only to the trailing paragraph

`AstParagraph` carries a `fadeOutEffect: Boolean`. When true, `Text` (`richtext-ui/.../string/Text.kt`) animates an
actual per-character reveal as the paragraph's text grows, instead of rendering the final text as-is. When a chat
message is mid-stream we want that only on the very last paragraph of the document — not on every paragraph.

Implementation (`Text.kt`)
- `revealedLength: Animatable<Float>` chases the text's growing length. Each time `text` changes, `LaunchedEffect`
  calls `animateTo(newLength)` — since `animateTo` on an already-animating `Animatable` interrupts and retargets
  smoothly, a chunk arriving mid-reveal eases onward instead of restarting. Duration is *clamped to a band*
  (`MIN_REVEAL_DURATION_MS`..`MAX_REVEAL_DURATION_MS`), not a fixed per-character rate — a fixed rate can't keep up
  with a fast stream, and letting the gap between `revealedLength` and the true length grow trips
  `MAX_ANIMATED_DELTA_CHARS` and snaps instead of animating. Clamping duration means a bigger burst just sweeps
  faster, so it (almost) always finishes inside the band regardless of size. `REVEAL_MS_PER_CHARACTER` (12ms) only
  actually determines the duration for delta roughly in `[15, 33]` characters — outside that band
  `MIN_REVEAL_DURATION_MS`/`MAX_REVEAL_DURATION_MS` dominate instead.
- `MAX_ANIMATED_DELTA_CHARS` (1500) is a backstop for genuinely pathological jumps only (attach/replay dumping a
  large backlog at once), not for ordinary fast streaming. Because duration is clamped to the band above, a bigger
  delta always sweeps *faster*, never slower — the old, much lower threshold assumed a fixed chars/sec rate where
  that wasn't true, so it tripped (and snapped, killing the fade entirely) under sustained fast streaming: an
  upstream coalescing buffer feeding chunks every ~100ms can park the backlog at several times the per-tick size
  once the tick rate can't fully drain it, comfortably exceeding a low threshold.
- The fade window is *not* a fixed character count. It's computed once per sweep, alongside `durationMs`, as
  `(delta / durationMs) * TARGET_CHAR_FADE_MS` (clamped to `[MIN_FADE_WINDOW_CHARS, MAX_FADE_WINDOW_CHARS]`) — so a
  character's own fade lasts roughly `TARGET_CHAR_FADE_MS` (120ms) of wall-clock time regardless of how fast the
  stream is moving, instead of a fixed 10-character window shrinking to a near-instant pop at high speed or
  stretching unnecessarily at low speed. Held in a `mutableFloatStateOf` set by the `LaunchedEffect`, since it's a
  property of the sweep, not of the frame.
- `applyAnimatedFadeEffect` renders: text well behind the wavefront untouched, a trailing gradient the width of that
  sweep's fade window (alpha ramps from `WAVEFRONT_MIN_ALPHA` (0.3) — not 0 — up to 1 with distance behind the
  wavefront, via the shared `wavefrontAlpha` helper), and everything beyond the wavefront hidden (alpha 0). The
  `WAVEFRONT_MIN_ALPHA` floor exists because the just-revealed edge of the text would otherwise land at
  `alpha ≈ 1/fadeWindow` (as low as ~1%) and sit there looking nearly invisible during backend think-time between
  chunks. Recomputed every animation frame, which is what makes a character's own transition long enough to read as
  a fade rather than a pop.
- Links resolve their own paint independently of any character-level `SpanStyle` — link color/decoration comes from
  `RichTextString.Format.Link`'s own resolved style, so an alpha `SpanStyle` on top of it has no visual effect. Any
  link position still ahead of the settled prefix has its annotation stripped and falls back to a plain,
  alpha-following character instead (via `text[i]`, not `content.subSequence`) — it only reappears fully styled once
  the wavefront has passed and settled beyond it. Without this, a link would flash into full visibility the instant
  its character exists in the string, ahead of not-yet-revealed plain text to its left. Link ranges are gathered
  once per content (`findLinkPositions`, `remember`-cached) rather than re-queried every frame, and the region beyond
  the wavefront is built run-based against those ranges — a bulk `subSequence` per plain gap under one shared alpha-0
  span, plus one stripped append per link run — instead of one `withStyle` + `subSequence(i, i+1)` per hidden
  character; that region can be the entire remaining unstreamed backlog (thousands of characters), so this keeps the
  cost O(links) rather than O(hidden characters). Only the fade-window region itself stays a per-character loop,
  since alpha varies continuously across it — bounded to at most `MAX_FADE_WINDOW_CHARS` (120) characters, so cheap
  either way. Every other, non-link character is rebuilt via `AnnotatedString.subSequence`, not raw `String`
  slicing, so incidental formatting (bold/italic) surviving the fade window isn't silently dropped.
- Inline content (e.g. a `<resource>`/citation badge, see addition #3 below) is handled differently from links, and
  differently from how it used to be: its placeholder character and annotation are now *always* kept intact via
  bulk `subSequence`, exactly like ordinary text — never stripped to a plain space. Substituting a space used to
  collapse the badge's `Placeholder` box down to a space's width until the wavefront passed it, then its full
  measured width would snap back in, visibly shoving everything after it sideways. Since the annotation is always
  preserved, the badge's full width is reserved in the text layout from the first frame it exists, and only its
  *paint* changes over time: `manageInlineTextContents`/`reifyInlineContent` (`InlineContent.kt`) now accept a
  per-tag `alpha` and apply it via `Modifier.graphicsLayer { this.alpha = alpha }` on the badge's own composable.
  `Text.kt` computes each badge's alpha from its placeholder's start offset (via
  `getStringAnnotations(INLINE_CONTENT_MARKER_TAG, ...)`, `remember`-cached per content) run through the same
  `wavefrontAlpha` ramp used for characters — fully revealed once settled, `WAVEFRONT_MIN_ALPHA`..1 while inside the
  fade window, `0` while still ahead of the wavefront.
- `CommonmarkAstNodeParser.parse` runs `findLastParagraph(...)` — an iterative DFS over the commonmark tree — to
  locate the last `Paragraph` node in document order, only when `options.fadeEffect == true`.
- That reference is threaded through `convert(..., lastParagraphNode = ...)` and into `convertNodeType(node, lastParagraphNode)`,
  which constructs `AstParagraph(fadeOutEffect = node === lastParagraphNode)`. Only the trailing paragraph is built
  with the flag set; every other paragraph gets `false`.
- No-op when the document contains no paragraphs (e.g., ends in a heading or fenced code block).

When syncing upstream, expect conflicts in:
- `richtext-commonmark/.../AstNodeConvert.kt` (`lastParagraphNode` parameter on `convert`/`convertNodeType`,
  the `is Paragraph ->` arm, `findLastParagraph` helper, and the `parse` call site)
- `richtext-ui/.../string/Text.kt` (the whole reveal-animation block and `applyAnimatedFadeEffect`)
- `richtext-ui/.../string/InlineContent.kt` (the `alpha`/`inlineContentAlphas` parameters threaded through
  `manageInlineTextContents`/`reifyInlineContent`, and the `graphicsLayer` modifier on the badge's `Layout`)
- `richtext-ui/.../string/RichTextString.kt` (`INLINE_CONTENT_MARKER_TAG` and the extra `addStringAnnotation` call
  in `Builder.appendInlineContent` — see addition #3)

### 3. Inline content marked with a first-party position annotation

`RichTextString.Builder.appendInlineContent` now also calls `addStringAnnotation(INLINE_CONTENT_MARKER_TAG, tag,
start, end)` over the placeholder character it inserts, in addition to what `androidx.compose.foundation.text
.appendInlineContent` already does internally. Compose Foundation tags inline content with its own internal
annotation tag, but that constant isn't public API — we don't want to depend on it (or its exact string value)
staying stable. `INLINE_CONTENT_MARKER_TAG` (`RichTextString.kt`) is our own stable tag consumers can query via
`AnnotatedString.getStringAnnotations` to find inline-content character ranges — used by addition #2 above to
locate each badge's start offset and drive its own reveal-alpha, independently of the surrounding text.

When syncing upstream, expect a conflict in `richtext-ui/.../string/RichTextString.kt`'s `appendInlineContent`.

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
