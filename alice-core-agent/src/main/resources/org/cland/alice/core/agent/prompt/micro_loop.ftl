<#-- Micro Loop Prompt (Micro-ReAct 微观循环) -->
<micro_loop>
<rules>
  <rule>Base your next action on the most recent tool result below.</rule>
  <rule>If the tool returned content you need, proceed to the next step. Do not re-read the same file.</rule>
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
