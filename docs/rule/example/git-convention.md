---
title: "Git Convention"
summary: "Follow Chris Beams style:"
priority: "high"
status: "enabled"
read_when:
  - "阅读/维护《Git Convention》时"
  - "排查与《Git Convention》相关问题"
---
## Commit Message Format

Follow [Chris Beams](http://chris.beams.io/posts/git-commit/) style:

```
<type>(<scope>): <subject>

<body>

<footer>
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

## Branch Naming

- Feature: `feat/<issue-number>-<short-desc>`
- Bugfix: `fix/<issue-number>-<short-desc>`
- Docs: `docs/<short-desc>`