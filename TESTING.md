# Testing the Plugin

This is a Gradle-based IntelliJ Platform plugin. JDK 21 is required (IDE 2024.2
runs on JBR 21). The Gradle wrapper is committed, so `./gradlew` works
straight from a clone.

## 1. Run the plugin in a sandbox IDE — fastest feedback loop

```bash
./gradlew runIde
```

This downloads IntelliJ IDEA Community 2024.2, installs the plugin into a
sandbox profile under `build/idea-sandbox/`, and launches it. Your real IDE
settings are untouched.

Inside the sandbox IDE:

1. Open or create any project that has the **Kotlin** plugin enabled (the
   sandbox IDE ships with it).
2. Create a file like `Demo.kt` and paste the snippet from
   [Sample fixture](#sample-fixture) below.
3. A small hierarchy icon appears in the gutter on the `strategy(` line.
   Click it to open the graph popup.
4. **Double-click a node** in the popup → caret jumps to the `val` declaration.
5. **Hover** an edge labeled `onToolCalls` / `onTextMessage` → tooltip shows
   the condition expression.
6. **Mouse wheel** over the popup → zoom in/out.

Stopping the sandbox IDE (just close it) returns control to the terminal.

### Hot iteration

`runIde` performs an incremental build. For code-only changes, stop the
sandbox IDE and re-run `./gradlew runIde` — Gradle's build cache makes the
restart fast. You can also keep the sandbox IDE running, change code, run
`./gradlew buildPlugin` in another terminal, then use `File → Reload All
Plugins` in the sandbox IDE.

## 2. Sample fixture

Paste this anywhere a Kotlin file is accepted in the sandbox project. The
plugin works on pure syntax, so the surrounding `strategy`/`nodeXxx` symbols
do **not** need to resolve — unresolved references are still rendered
(declared nodes as solid boxes, unknown references as dashed yellow boxes).

```kotlin
fun streamConsolidator(): Any = Unit

fun demo() {
    strategy<String, String>("koog_streaming") {
        val callLLM by nodeLLMRequestStreaming()
        val consolidateInitial by streamConsolidator()
        val executeTool by nodeExecuteTools()
        val sendToolResult by nodeLLMSendToolResultsStreaming()
        val consolidateAfterTool by streamConsolidator()

        edge(nodeStart forwardTo callLLM)
        edge(callLLM forwardTo consolidateInitial)
        edge(consolidateInitial forwardTo executeTool onToolCalls { true })
        edge(consolidateInitial forwardTo nodeFinish onTextMessage { true })
        edge(executeTool forwardTo sendToolResult)
        edge(sendToolResult forwardTo consolidateAfterTool)
        edge(consolidateAfterTool forwardTo executeTool onToolCalls { true })
        edge(consolidateAfterTool forwardTo nodeFinish onTextMessage { true })
    }
}
```

You should see seven nodes (`nodeStart` and `nodeFinish` as Start/Finish in
green/red, the five `val`-declared nodes in blue) and eight edges, four of
them labeled with their condition name.

### Big fixture — branches, back-edges, multiple conditions, unknown ref

Stress-tests the layered layout: a research-agent shape with 13 declared
nodes, 22 edges, two tool-execution loops that branch on tool name, a
judge node with three different outcomes, and one undeclared reference
(`escalate`) to show off the dashed-yellow Unknown style.

```kotlin
fun streamConsolidator(): Any = Unit
fun nodeJudge(): Any = Unit
fun nodeSummarize(): Any = Unit
fun nodeHandleError(): Any = Unit

fun researchAgent() {
    strategy<UserQuery, FinalReport>("research_agent") {
        val planResearch by nodeLLMRequestStreaming()
        val consolidatePlan by streamConsolidator()

        val executeSearch by nodeExecuteTools()
        val readSearch by nodeLLMSendToolResultsStreaming()
        val consolidateSearch by streamConsolidator()

        val executeFetch by nodeExecuteTools()
        val readFetch by nodeLLMSendToolResultsStreaming()
        val consolidateFetch by streamConsolidator()

        val judge by nodeJudge()
        val refineQuestion by nodeLLMRequestStreaming()
        val consolidateRefine by streamConsolidator()

        val summarize by nodeSummarize()
        val handleError by nodeHandleError()

        // Planning phase
        edge(nodeStart forwardTo planResearch)
        edge(planResearch forwardTo consolidatePlan)
        edge(consolidatePlan forwardTo executeSearch onToolCalls { true })
        edge(consolidatePlan forwardTo handleError onError { true })

        // Search loop — can re-search or hand off to fetch
        edge(executeSearch forwardTo readSearch)
        edge(readSearch forwardTo consolidateSearch)
        edge(consolidateSearch forwardTo executeFetch onToolCalls { name == "fetch" })
        edge(consolidateSearch forwardTo executeSearch onToolCalls { name == "search" })
        edge(consolidateSearch forwardTo judge onTextMessage { true })

        // Fetch loop — can re-fetch or hand back to search
        edge(executeFetch forwardTo readFetch)
        edge(readFetch forwardTo consolidateFetch)
        edge(consolidateFetch forwardTo executeFetch onToolCalls { name == "fetch" })
        edge(consolidateFetch forwardTo executeSearch onToolCalls { name == "search" })
        edge(consolidateFetch forwardTo judge onTextMessage { true })

        // Judge fans out three ways — note `escalate` is referenced but
        // never declared, so it renders as a dashed-yellow Unknown box.
        edge(judge forwardTo summarize onApproved { confidence > 0.8 })
        edge(judge forwardTo refineQuestion onRejected { true })
        edge(judge forwardTo escalate onError { true })

        // Refinement re-enters the search loop
        edge(refineQuestion forwardTo consolidateRefine)
        edge(consolidateRefine forwardTo executeSearch onToolCalls { true })
        edge(consolidateRefine forwardTo judge onTextMessage { true })

        edge(summarize forwardTo nodeFinish)
        edge(handleError forwardTo nodeFinish)
    }
}
```

What to look for after clicking the gutter icon:

- **15 nodes**: green `nodeStart`, red `nodeFinish`, 13 blue declared, and
  one dashed-yellow `escalate` (referenced, never declared).
- **Back-edges** from `consolidateSearch`, `consolidateFetch`, and
  `consolidateRefine` looping into earlier `execute*` nodes — ELK reverses
  them under the hood and routes them around the side of the diagram.
- **Five distinct condition labels** (`onToolCalls`, `onTextMessage`,
  `onError`, `onApproved`, `onRejected`) — all sit on edge midpoints
  without overlapping boxes.
- Hovering an `onToolCalls` edge whose lambda is `{ name == "fetch" }` or
  `{ confidence > 0.8 }` shows the full predicate in the tooltip.
- Double-clicking `judge` jumps the caret to `val judge by nodeJudge()`.

## 3. Unit tests

```bash
./gradlew test
```

The parser tests use `BasePlatformTestCase` from the IntelliJ test framework,
so a headless IDE is spun up per test class. First run downloads test deps;
expect ~30 s the first time, ~5 s after that.

To run a single test:

```bash
./gradlew test --tests "io.github.jacekgajek.koog.graph.parser.StrategyParserTest.testGoalExample"
```

Test reports land in `build/reports/tests/test/index.html`.

## 4. Verify against the marketplace plugin verifier

```bash
./gradlew verifyPlugin
```

Catches binary-compat issues with the target IDE range. Run before publishing.

## 5. Build a distributable ZIP

```bash
./gradlew buildPlugin
```

Output: `build/distributions/koog-strategy-graph-plugin-<version>.zip` — drop
that into any IDE 2024.2+ via *Settings → Plugins → Install plugin from disk*.

## 6. Debugging tips

- **Sandbox IDE logs**: `build/idea-sandbox/system/log/idea.log`. Watching
  with `tail -F` while clicking the gutter icon is the fastest way to catch
  exceptions in the parser or renderer.
- **Attach a debugger**: run `./gradlew runIde --debug-jvm`. The sandbox IDE
  pauses on startup waiting for a JDWP connection on port 5005 — connect
  IntelliJ's "Remote JVM Debug" run config to it. Breakpoints in
  `StrategyParser`, `ElkLayout`, or `GraphPanel` all work.
- **Force a re-parse**: edit and save the file — the gutter icon re-runs the
  provider on every PSI change, so adding/removing whitespace inside the
  `strategy { }` block is the simplest way to invalidate.
- **Inspect ELK layout**: temporarily set
  `root.setProperty(CoreOptions.DEBUG_MODE, true)` in `ElkLayout.kt` to write
  per-phase diagnostics into `idea.log`.
- **Theme check**: the popup uses `JBColor`, so toggling between Light and
  Dark in *Settings → Appearance* should re-color the graph on next open.
