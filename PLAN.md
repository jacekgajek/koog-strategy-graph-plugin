# Koog Strategy Graph Plugin — Implementation Plan

An IntelliJ IDEA plugin that detects `strategy<I, O>("...") { ... }` DSL blocks
from the Koog library and renders an inline graph visualization (nodes + edges)
directly in the editor.

---

## 1. Project Structure

Standard IntelliJ Platform plugin layout, built with the Kotlin Gradle DSL and
the official `org.jetbrains.intellij.platform` Gradle plugin (the successor to
`gradle-intellij-plugin`).

```
koog-strategy-graph-plugin/
├── build.gradle.kts                  # Kotlin DSL build, IntelliJ Platform plugin
├── settings.gradle.kts
├── gradle.properties                 # platformVersion, pluginVersion, kotlin.code.style
├── gradle/
│   └── libs.versions.toml            # Version catalog
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── io/github/jacekgajek/koog/graph/
│   │   │       ├── KoogPlugin.kt                       # Plugin entry / startup
│   │   │       ├── parser/
│   │   │       │   ├── StrategyParser.kt               # PSI traversal of strategy { } blocks
│   │   │       │   ├── StrategyModel.kt                # Pure data: Node, Edge, Graph
│   │   │       │   ├── NodeExtractor.kt                # `val x by nodeXxx()` recognition
│   │   │       │   └── EdgeExtractor.kt                # `edge(a forwardTo b onXxx { })`
│   │   │       ├── marker/
│   │   │       │   └── StrategyLineMarkerProvider.kt   # Gutter icon on `strategy(...)`
│   │   │       ├── codevision/
│   │   │       │   └── StrategyCodeVisionProvider.kt   # Inlay "Show graph" affordance
│   │   │       ├── render/
│   │   │       │   ├── GraphRenderer.kt                # Interface; impl chosen via SPI
│   │   │       │   ├── ElkLayout.kt                    # Layered layout via ELK
│   │   │       │   └── Swing/
│   │   │       │       ├── GraphPanel.kt               # JComponent rendering
│   │   │       │       └── GraphPopup.kt               # JBPopup wrapper
│   │   │       ├── action/
│   │   │       │   └── ShowStrategyGraphAction.kt      # AnAction triggered by marker
│   │   │       └── settings/
│   │   │           └── KoogGraphSettings.kt            # PersistentStateComponent
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   ├── plugin.xml                          # Extensions: lineMarker, action
│   │       │   └── pluginIcon.svg
│   │       └── icons/
│   │           └── strategyGraph.svg
│   └── test/
│       └── kotlin/
│           └── io/github/jacekgajek/koog/graph/
│               ├── parser/StrategyParserTest.kt        # Light PSI fixture tests
│               └── render/ElkLayoutTest.kt
├── PLAN.md
└── GOAL.md
```

**Toolchain**

| Tool                         | Version (target)                                  |
|------------------------------|---------------------------------------------------|
| Kotlin                       | 2.1.x                                             |
| Gradle                       | 8.10+ (Kotlin DSL)                                |
| `intellij-platform` plugin   | 2.x                                               |
| Target IDE                   | IntelliJ IDEA 2024.2+ (`sinceBuild=242`)          |
| Required IDE plugins         | `com.intellij.modules.platform`, `org.jetbrains.kotlin` |

`org.jetbrains.kotlin` dependency is required because parsing relies on Kotlin
PSI types (`KtCallExpression`, `KtProperty`, etc.).

---

## 2. Parsing Implementation

### 2.1 What we are looking for

A strategy block has three signal shapes:

1. **The DSL anchor** — a top-level `strategy<I, O>("name") { ... }` call.
2. **Node declarations** inside the lambda — `val <id> by <factoryCall>()`
   where the factory typically begins with `node` (`nodeStart`, `nodeFinish`,
   `nodeLLMRequestStreaming`, ...). Plus the two well-known constants
   `nodeStart` and `nodeFinish` referenced by name without a `by` delegate.
3. **Edge declarations** — `edge(<from> forwardTo <to>[ <condCall> { ... }])`
   inside the same lambda.

### 2.2 Approach

Use the Kotlin PSI directly (no reflection, no source-eval). The plugin runs in
the IDE process where the Kotlin PSI is always available.

```kotlin
class StrategyParser {
    fun parse(call: KtCallExpression): StrategyGraph? {
        // 1. Verify callee text == "strategy" (cheap heuristic) and confirm via
        //    resolved descriptor when available (analysis-api or KtResolutionFacade).
        // 2. Extract type args -> input/output type names (strings only; we don't
        //    need real types).
        // 3. Read first string literal arg -> strategy name.
        // 4. Walk the trailing lambda body once:
        //      - KtProperty with a `by` delegate -> NodeExtractor
        //      - KtCallExpression named "edge"   -> EdgeExtractor
        // 5. Return StrategyGraph(name, nodes, edges, sourceRange).
    }
}
```

### 2.3 Resilience constraints

- **Best-effort**: unresolved references must not throw. An unknown factory
  becomes a generic `Node(kind=Unknown)` with the source text as its label.
- **Lambda-only scan**: we only descend into the strategy's trailing lambda,
  not arbitrary call sites, so cost is bounded by the block size.
- **Read action**: all PSI access wrapped in `ReadAction.compute { ... }`.
- **Caching**: results keyed by `(virtualFile, callExpression.textRange,
  modificationStamp)` and stored via `CachedValuesManager` so reopening the
  popup is instant until the user edits.
- **Dumb mode**: parser must work in dumb mode (no indexes required); we lean
  on syntax only, not resolved types.

### 2.4 Edge extraction details

`a forwardTo b` is a Kotlin infix call: `KtBinaryExpression` with operation
reference `forwardTo`. The optional condition `onToolCalls { true }` chains
after as another infix call. The extractor walks the left/right of each binary
expression, recording:

```kotlin
data class Edge(
    val from: NodeRef,              // by simple name
    val to: NodeRef,
    val condition: ConditionKind?,  // onToolCalls / onTextMessage / null / Unknown
    val conditionExpr: String?,     // verbatim source for tooltip
)
```

Node references are resolved by name against the node list collected in pass 1.
Unknown names are kept and rendered as dashed/red so authors notice typos.

---

## 3. Rendering Plan

### 3.1 Discovery in the editor

Two complementary entry points:

- **Gutter icon** (`LineMarkerProvider`): a small graph icon next to the
  `strategy(` line. Clicking opens the popup.
- **Code Vision / Inlay** (`CodeVisionProvider`): an inline "Show graph"
  affordance above the `strategy(` line for quick discovery.

Both invoke the same `ShowStrategyGraphAction`.

### 3.2 Popup

- `JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel)`
- Resizable, movable, dismiss on `Esc`/focus loss.
- Default size: 720×520, persisted in `KoogGraphSettings`.
- Toolbar: zoom in/out, fit, copy-as-PNG, jump-to-node (clicking a node moves
  the caret to the declaration).

### 3.3 Layout pipeline

```
StrategyGraph  ──►  Layout engine (ELK / mxGraph)  ──►  Positioned graph
                                                              │
                                                              ▼
                                                  Swing JComponent (paint)
```

- Layered (Sugiyama) layout, top-to-bottom.
- Edge labels show the condition (`onToolCalls`, `onTextMessage`); unconditional
  edges are unlabeled.
- `nodeStart` and `nodeFinish` get distinct shapes (rounded green / red).

### 3.4 Interaction

- Hover a node → tooltip with full factory call source.
- Double-click a node → caret jumps to its `val` declaration.
- Hover an edge → tooltip with verbatim condition expression.
- Live update: a `PsiTreeChangeListener` invalidates the cached graph and, if
  the popup is open, re-renders.

### 3.5 Theming

Use `JBColor` + `EditorColorsManager` so the graph follows IDE light/dark
themes. No hard-coded colors.

---

## 4. Graph-Drawing Library Choices

Requirement: **no external tools installed on the developer machine**. That
rules out anything that shells out to a `dot` / `graphviz` binary or requires
Node/Python at runtime.

Four pure-JVM options, ranked by fit:

### Option A — **ELK (Eclipse Layout Kernel) + custom Swing painter** *(recommended)*

- **What it is**: pure-Java *layout* engine (no rendering). Industry-strength
  layered/Sugiyama, force, radial layouts. Used by Sirius, GLSP, the Eclipse
  modeling ecosystem.
- **Artifact**: `org.eclipse.elk:elk-core` + `org.eclipse.elk:elk-alg-layered`.
- **Rendering**: we draw the positioned graph ourselves with Swing's
  `Graphics2D`. ~200–400 LOC of straightforward painting code.
- **Pros**: best layered layouts for this DSL shape; small dependency
  footprint (~1.5 MB); zero native deps; very actively maintained; layout and
  rendering are decoupled so theming/zoom are trivial.
- **Cons**: we own the rendering code (interaction, hit-testing, edge routing
  display). For a graph this small that is a feature, not a bug.

### Option B — **JGraphX (mxGraph Swing)**

- **What it is**: full Swing graph component — model, layouts, renderer,
  interaction — in one library.
- **Artifact**: `com.github.vlsi.mxgraph:jgraphx` (community fork; original is
  archived/dual-licensed).
- **Pros**: out-of-the-box pan/zoom/hit-testing/labels; quickest path to a
  working popup.
- **Cons**: original mxGraph is archived; the active fork is community-led.
  Heavier API surface, AWT/Swing styling collides slightly with IntelliJ's
  `JBColor`/UI themes. Acceptable as a fallback if Option A's hand-painted
  renderer becomes a sink.

### Option C — **graphviz-java (nidi3) with the bundled pure-Java engine**

- **What it is**: a Kotlin/Java DSL that emits DOT and renders it. The
  `guru.nidi:graphviz-java` artifact ships a **pure-Java** engine using a
  bundled V8/JS Graphviz build — no `dot` binary on the user's machine.
- **Pros**: trivially good-looking output; familiar DOT model.
- **Cons**: large dependency (~10–15 MB), startup cost from the embedded JS
  engine, renders to raster/SVG (less interactive — clicking a node back to
  source requires SVG hit-mapping). Best when interaction is not a priority.

### Option D — **JUNG2 / JGraphT + custom Swing**

- **What it is**: JGraphT provides the graph data model and algorithms;
  JUNG2 provides Swing visualization (project is older but still works on
  modern JDKs).
- **Pros**: very mature; small; layered layout available via JGraphT's
  `BarycenterLayout` or external port.
- **Cons**: JUNG2 has not seen active releases recently; layered layout
  quality is below ELK. Reasonable for a minimal first cut but likely to be
  replaced.

### Recommendation

**Adopt Option A (ELK + Swing).** It gives the best layout quality for the
layered, mostly-DAG shape of Koog strategies, it has no native dependencies, and
keeping rendering in our own Swing component makes IntelliJ-native interactions
(caret navigation, theming, popups, accessibility) straightforward.

**Fallback path**: if ELK's API friction exceeds expectations during the layout
milestone, swap in **Option B (JGraphX)** behind the `GraphRenderer` interface
defined in `render/GraphRenderer.kt`. The parser and model layer are
unaffected.

---

## 5. Milestones

1. **M1 — Scaffold**: `intellij-platform` Gradle project, empty plugin loads in
   a sandbox IDE.
2. **M2 — Parser**: extract `StrategyGraph` from PSI; unit-tested against the
   GOAL.md example and 3–4 hand-written variants.
3. **M3 — Layout**: ELK pipeline producing positioned nodes/edges; covered by a
   golden test on coordinates.
4. **M4 — Popup**: `LineMarkerProvider` + `ShowStrategyGraphAction` + Swing
   panel that paints the positioned graph.
5. **M5 — Interaction**: node click → caret jump, hover tooltips, theme
   awareness.
6. **M6 — Polish**: code-vision affordance, settings (popup size, layout
   direction), zoom/fit/export PNG.
