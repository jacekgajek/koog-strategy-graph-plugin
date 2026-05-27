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
