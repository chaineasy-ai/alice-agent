<#-- Micro Loop Error Prompt (工具执行失败) -->
<#-- error rules are injected dynamically by PromptManager.buildRules("error") -->
<tool_error>
  <tool>${toolName}</tool>
  <message>${errorMessage}</message>
</tool_error>
<instruction>The tool above failed. Fix the issue or use an alternative approach.</instruction>
