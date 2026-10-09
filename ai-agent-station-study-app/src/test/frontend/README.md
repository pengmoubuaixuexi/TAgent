# Workspace browser contract checks

`workspace-ui.integration.cjs` runs the real static pages in headless Chromium against an ephemeral HTTP server and deterministic API fixtures. It does not contact MySQL, external MCP servers, or a deployed TAgent instance. Install/provide the `playwright` Node package before running; `NODE_PATH` can point to an existing package directory. Set `CHROME_PATH` to a local Chrome/Chromium executable when it is not installed at the standard Windows Chrome path. The same variable is used by `observe-names.integration.cjs` and `run-replay.integration.cjs`.

```powershell
node .\ai-agent-station-study-app\src\test\frontend\workspace-ui.integration.cjs
```

The checks cover Agent creation/update and RAG parameters, optimistic-conflict input preservation, MCP connection binding and explicit conversion of legacy per-tool restrictions, token retention/replacement/clearing, protected preset controls, draft review before saving, public-Agent and builder entry, per-user versus administrator observation requests, administrator-only site-statistics loading, and responsive layouts. Screenshots are saved to the temporary directory printed by the test. They contain fixture data only.

These checks verify the browser/API contract, not server-side authorization. Run the Java workspace/security tests separately for ownership and permission enforcement.
