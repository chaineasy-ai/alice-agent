<#-- Planner Prompt — LLM 输出意图链，每行一个意图 -->
<#-- planner rules are injected dynamically by PromptManager.buildRules("planner") -->
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

<task>Respond with one or more words, space-separated, from the list above. Example: ANALYZE SEARCH CODE GENERATE</task>
