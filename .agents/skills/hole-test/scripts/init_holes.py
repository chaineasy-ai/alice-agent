#!/usr/bin/env python3
"""
Initialize hole test scaffolding for a new module.

Usage:
  python init_holes.py <module-name> <prefix> <num-holes>

Example:
  python init_holes.py alice-foo-bar FOO 4

Creates:
  docs/alice-agent-command/e2e/case-<module>.md
  docs/<module>/e2e/scene-<module>-endpoints.md
  docs/<module>/e2e/hole_test_<module>.py
"""

import os
import sys
import shutil

TEMPLATE_CASE = """\
---
title: "E2E Case — {module} endpoints"
summary: "Hole test specification for {module} module — public API boundaries."
read_when:
  - "implementing or modifying hole tests for {module}"
scope:
  - "alice-agent-command"
  - "{module}"
status: "active"
updated: "2026-06-19"
---

# E2E Case — {module} (Hole Test)

## 1. Purpose

Probe the **{module}** module's public API boundary.

## 2. Hole Design

{HOLE_DESIGN}

## 3. Hole Tests

{HOLE_TESTS}
"""

TEMPLATE_SCENE = """\
---
title: "Hole Scene — {module} endpoints"
summary: "Module-level hole tests probing {module} public API boundaries."
read_when:
  - "running or debugging hole tests for {module}"
scope:
  - "{module}"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — {module} Endpoints

## 1. Scene Overview

{num_holes} hole probes into the `{module}` module.

**Case doc**: `docs/alice-agent-command/e2e/case-{module}.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│              {module}               │
│                                     │
{HOLE_MAP}
└─────────────────────────────────────┘
```

## 3. How to Run

```bash
python docs/{module}/e2e/hole_test_{module}.py
```
"""

TEMPLATE_IMPL = """\
#!/usr/bin/env python3
"""
Hole Test — {module} module endpoints.

See:
  docs/alice-agent-command/e2e/case-{module}.md
  docs/{module}/e2e/scene-{module}-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


class Test{module_camel}Holes(unittest.TestCase):
    """Hole tests for {module} — {num_holes} probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "{module}" / "build").is_dir()

{HOLE_METHODS}

if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: {module}")
    print(f"  Module: {{PROJECT_ROOT / '{module}'}}")
    print("=" * 60)
    unittest.main(verbosity=2)
"""


def to_camel(name):
    """Convert kebab-case to CamelCase."""
    return "".join(word.capitalize() for word in name.split("-"))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    module = sys.argv[1]
    prefix = sys.argv[2].upper()
    num_holes = int(sys.argv[3]) if len(sys.argv) > 3 else 4

    project_root = os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "..", "..", "..")
    )

    # Build hole definitions
    holes = []
    for i in range(1, num_holes + 1):
        holes.append({
            "id": f"{prefix}-P{i:02d}",
            "desc": f"TODO: define probe {prefix}-P{i:02d}",
            "target": "TargetClass.methodName()",
            "input": "TODO",
            "expected": "TODO",
        })

    # Generate case doc
    hole_design_lines = []
    hole_test_lines = []
    hole_map_lines = []
    hole_methods = []

    for h in holes:
        hid = h["id"]
        hole_design_lines.append(f"### {hid}: {h['desc']}")
        hole_design_lines.append("")
        hole_design_lines.append(f"| Field | Value |")
        hole_design_lines.append(f"|-------|-------|")
        hole_design_lines.append(f"| **Target** | `{h['target']}` |")
        hole_design_lines.append(f"| **Input** | {h['input']} |")
        hole_design_lines.append(f"| **Expected** | {h['expected']} |")
        hole_design_lines.append(f"| **Assertion** | TODO |")
        hole_design_lines.append("")

        hole_map_lines.append(f"  {hid}  {h['target']}")

        hole_methods.append(f"""\
    @unittest.skip("TODO: implement {hid}")
    def test_{hid.lower()}(self):
        \"\"\"{hid}: {h['desc']}\"\"\"
        pass
""")

    HOLE_DESIGN = "\n".join(hole_design_lines)
    HOLE_TESTS = "\n".join(f"TODO: {h['id']} — {h['desc']}" for h in holes)
    HOLE_MAP = "\n".join(f"  │  {m}" for m in hole_map_lines)
    HOLE_METHODS = "\n".join(hole_methods)

    module_camel = to_camel(module)

    case_content = TEMPLATE_CASE.format(
        module=module,
        HOLE_DESIGN=HOLE_DESIGN,
        HOLE_TESTS=HOLE_TESTS,
    )

    scene_content = TEMPLATE_SCENE.format(
        module=module,
        num_holes=num_holes,
        HOLE_MAP=HOLE_MAP,
    )

    impl_content = TEMPLATE_IMPL.format(
        module=module,
        module_camel=module_camel,
        num_holes=num_holes,
        HOLE_METHODS=HOLE_METHODS,
    )

    # Write files
    case_dir = os.path.join(project_root, "docs", "alice-agent-command", "e2e")
    scene_dir = os.path.join(project_root, "docs", module, "e2e")
    impl_dir = scene_dir

    os.makedirs(case_dir, exist_ok=True)
    os.makedirs(scene_dir, exist_ok=True)

    case_path = os.path.join(case_dir, f"case-{module}.md")
    scene_path = os.path.join(scene_dir, f"scene-{module}-endpoints.md")
    impl_path = os.path.join(impl_dir, f"hole_test_{module}.py")

    for path, content in [(case_path, case_content),
                          (scene_path, scene_content),
                          (impl_path, impl_content)]:
        with open(path, "w") as f:
            f.write(content)
        print(f"  ✦ {path}")

    print(f"\n  ✅ {num_holes} holes ({prefix}-P01~P{num_holes:02d}) scaffolded for {module}")
    print(f"  Edit each hole's target/input/expected to fill in the blanks.")


if __name__ == "__main__":
    main()
