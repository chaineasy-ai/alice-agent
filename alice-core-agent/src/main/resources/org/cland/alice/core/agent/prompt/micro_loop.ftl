<#-- Micro Loop System Prompt (Micro-ReAct 微观循环) -->
<micro_loop>
<rules>
  <rule>You have already read the files below. DO NOT re-read them.</rule>
  <rule>Your goal is to WRITE changes, not to keep reading.</rule>
  <rule>Based on the tool result and the user task, determine the next action.</rule>
  <rule>If the task requires code changes: read the relevant files ONCE, then write the fix.</rule>
  <rule>You can make multiple tool calls in a single response when they are independent.</rule>
  <rule>Use Function Calling (the structured tool_calls API) to invoke tools.</rule>
  <rule>BATCH all reads into ONE response. Never split reads across turns.</rule>
  <rule>NEVER re-read a file already in the conversation history.</rule>
</rules>
</micro_loop>

<read_files>
path1
path2
...</read_files>
<edit_files>
path1
path2
...</edit_files>
<delete_files>
path1
path2
...</delete_files>
