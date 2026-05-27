# Koog Strategy Graph Plugin

An Intellij Idea plugin which renders in-line visualization of koog strategy statements.

Koog allows creating custom strategies for agents which look like this:

```
strategy<String, String>("koog_streaming") {
    val callLLM by nodeLLMRequestStreaming()
    val consolidateInitial by streamConsolidator()
    val executeTool by nodeExecuteTools()
    val sendToolResult by nodeLLMSendToolResultsStreaming()
    val consolidateAfterTool by streamConsolidator()

    edge(nodeStart forwardTo callLLM)
    edge(callLLM forwardTo consolidateInitial)
    // `onToolCalls` MUST precede `onTextMessage`: an assistant message can contain both, and
    // routing to nodeFinish first abandons the tool_call — OpenAI then 400s the next turn
    // ("tool_call_ids did not have response messages"). Koog's reference `singleRunStrategy`
    // has the same bug post-tool-results; we don't.
    edge(consolidateInitial forwardTo executeTool onToolCalls { true })
    edge(consolidateInitial forwardTo nodeFinish onTextMessage { true })
    edge(executeTool forwardTo sendToolResult)
    edge(sendToolResult forwardTo consolidateAfterTool)
    edge(consolidateAfterTool forwardTo executeTool onToolCalls { true })
    edge(consolidateAfterTool forwardTo nodeFinish onTextMessage { true })
  }
```

Your goal is to create an IDE plugin which adds an inline popup which shows the visualization of it - a graph which has all nodes and edges.

1. Use latest kotlin gradle as build system.
2. Use common conventions for IntelliJ Idea plugins
3. Create a PLAN.md file which contains
  - Project structure
  - Parsing implementation plan
  - Rendering plan
  - Choice of graph-drawing libraries. Do not require installation of external tools on local machine.
     - give several options.
 

