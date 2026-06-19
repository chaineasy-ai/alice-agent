import re

# Simulate the LLM output containing the tool call
output = '[TOOL_CALL: read_file(path="e2e/smoke/fixtures/math_utils/math_utils.py")]'
pattern = r'\[TOOL_CALL:\s*(\w+)\(([^)]*)\)\]'
m = re.search(pattern, output)
print('Match:', m)
if m:
    print('tool:', m.group(1))
    print('params:', m.group(2))
else:
    print('NO MATCH')

# Also test with the actual LLM output format (might have unicode chars)
output2 = '思考\n[TOOL_CALL: read_file(path="e2e/smoke/fixtures/math_utils/math_utils.py")]'
m2 = re.search(pattern, output2)
print('Match2:', m2)
