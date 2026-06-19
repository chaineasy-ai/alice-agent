<#-- Micro Loop Error Prompt (工具执行失败) -->
<micro_loop>
<rules>
  <rule>The tool call below failed. Diagnose the error before retrying.</rule>
  <rule>Do not retry the same call with identical parameters.</rule>
  <rule>If the error is unrecoverable, output [FINISH] with a summary.</rule>
</rules>
<tool_error>
  <tool>${toolName}</tool>
  <message>${errorMessage}</message>
</tool_error>
<instruction>The tool above failed. Fix the issue or use an alternative approach.
If the task is impossible, output [FINISH] with a summary.</instruction>
</micro_loop>
