<#-- Micro Loop Prompt (Micro-ReAct 微观循环) -->
<micro_loop>
<rules>
  <rule>You have already read the files below. DO NOT re-read them.</rule>
  <rule>Your goal is to WRITE changes, not to keep reading.</rule>
  <rule>Based on the tool result and the user task, determine the next action.</rule>
  <rule>If the task requires code changes: read the relevant files ONCE, then write the fix.</rule>
  <rule>You can make multiple tool calls in a single response when they are independent.</rule>
  <rule>Use Function Calling (the structured tool_calls API) to invoke tools.</rule>
</rules>

<user_task>
${userTask}
</user_task>

<tool_result>
${toolResult}
</tool_result>

Continue working on the user task above.
</micro_loop>
