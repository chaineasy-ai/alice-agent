<#-- Planner Prompt (由 PlannerService/Strategy 注入) -->
<planner>
<rules>
  <rule>Analyze the context and produce a Plan with steps.</rule>
  <rule>Each step must be one of: LLM_INFERENCE, TOOL_CALL, FINISH.</rule>
  <rule>If the task is already complete, produce a single FINISH step.</rule>
</rules>
<user_task>
${userTask}
</user_task>

<#if lastObservation?? && lastObservation?has_content>
<last_observation>
${lastObservation}
</last_observation>
</#if>

<#if lastActionResult?? && lastActionResult?has_content>
<last_action_result>
${lastActionResult}
</last_action_result>
</#if>

<#if error?? && error?has_content>
<error>
${error}
</error>
</#if>

<task>Analyze the above and produce a Plan with steps to complete the user task. Each step should be one of: LLM_INFERENCE, TOOL_CALL, FINISH.</task>
</planner>
