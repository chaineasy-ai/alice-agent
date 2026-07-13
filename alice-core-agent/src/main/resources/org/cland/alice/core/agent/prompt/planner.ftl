<#-- Planner Prompt — LLM 输出意图链，每行一个意图 -->
<planner>
<rules>
  <rule>Analyze the user request and output a plan as ONE OR MORE lines.</rule>
  <rule>Each line is exactly one word from the list below.</rule>
  <rule>Available intents:</rule>
  <rule>ANALYZE  — needs understanding, reasoning, or explanation</rule>
  <rule>SEARCH   — needs to look up external information</rule>
  <rule>CODE     — needs to write or modify code</rule>
  <rule>GENERATE — needs to create content (docs, text, etc.)</rule>
  <rule>ANSWER   — simple greeting or direct response, no tools needed</rule>
  <rule>FINISH   — task already complete</rule>
  <rule>Example: "SEARCH ANALYZE GENERATE" for a research-then-write task.</rule>
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

<task>Respond with one or more words, space-separated, from the list above. Example: ANALYZE SEARCH CODE GENERATE</task>
</planner>
