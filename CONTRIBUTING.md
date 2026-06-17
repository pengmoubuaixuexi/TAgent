# Contributing

Thanks for your interest in TAgent. This repository is a sanitized public version of an AI Agent engineering project. Contributions are welcome, especially in documentation, reproducible examples, bug reports, and focused fixes.

## Good First Contributions

- Improve README examples or deployment notes.
- Add reproducible smoke tests for Agent, MCP, RAG, or SSE flows.
- Report unclear setup steps with logs and environment details.
- Propose new MCP tool governance cases or observability improvements.

## Local Check

Before opening a pull request, run:

```bash
mvn -pl ai-agent-station-study-trigger -am clean test-compile
```

## Pull Request Notes

- Keep changes focused and explain the user-visible impact.
- Do not commit API keys, database dumps, runtime logs, chat histories, or local credentials.
- For behavior changes, include a short verification note in the PR description.
- For docs changes, prefer concise examples that can be reproduced by a new reader.

