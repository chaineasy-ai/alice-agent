<#-- Micro Loop Prompt (Micro-ReAct 微观循环) -->
<micro_loop>
<rules>
  <rule>You have already read the files below. DO NOT re-read them.</rule>
  <rule>Your goal is to WRITE changes, not to keep reading.</rule>
  <rule>Based on the tool result and the user task, determine the next action.</rule>
  <rule>If you have all file contents, proceed directly to write_file.</rule>
  <rule>Output at most one [TOOL_CALL:] or [FINISH] per response.</rule>
  <rule>When the task is complete, output [FINISH]. Do not add extra commentary.</rule>
</rules>

<user_task>
${userTask}
</user_task>

<tool_result>
${toolResult}
</tool_result>

Continue working on the user task above. If the task is complete, output [FINISH].
</micro_loop>
