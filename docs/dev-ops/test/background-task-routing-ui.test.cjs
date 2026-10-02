// Run with: node docs/dev-ops/test/background-task-routing-ui.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.resolve(__dirname, '../../../ai-agent-station-study-app/src/main/resources/static/index.html'), 'utf8');
for (const [, script] of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) new vm.Script(script);
const functions = ['tryHandleBackgroundTaskCommand', 'backgroundTaskAgentLabel', 'backgroundTaskAgentControl',
  'updateBackgroundTaskAgent', 'runBackgroundTaskAction'];
const source = functions.map(name => {
  const start = html.search(new RegExp(`^(?:async )?function ${name}\\(`, 'm'));
  assert.ok(start >= 0, name);
  const rest = html.slice(start);
  const next = rest.slice(1).search(/^(?:async )?function \w+\(/m);
  return rest.slice(0, next + 1);
}).join('\n');
const requests = [];
const alerts = [];
let response = {code: '0000', data: {matched: false}};
const context = vm.createContext({
  state: {userId: 'user', sessionId: 'session', currentAgentId: 'chat-agent', maxStep: 5,
    agents: [{agentId: 'chosen', agentName: 'Chosen'}]},
  API: '/api/v1/agent', escapeHtml: value => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;'),
  fetch: async (url, options) => { requests.push({url, body: JSON.parse(options.body)}); return {json: async () => response}; },
  document: {getElementById: () => ({classList: {contains: () => true}})},
  alert: message => alerts.push(message),
});
vm.runInContext(source, context);
(async () => {
  assert.equal(await context.tryHandleBackgroundTaskCommand('hello', []), false);
  assert.equal(Object.hasOwn(requests[0].body, 'aiAgentId'), false, 'chat choice must not enter task creation');
  const automatic = context.backgroundTaskAgentControl({status: 'DRAFT', actionAgentId: null});
  assert.match(automatic, /value="" selected/);
  const pinned = context.backgroundTaskAgentControl({status: 'DRAFT', actionAgentId: 'chosen'});
  assert.match(pinned, /value="chosen" selected/);
  const legacy = context.backgroundTaskAgentControl({status: 'DRAFT', actionAgentId: 'retired'});
  assert.match(legacy, /value="retired" selected/, 'unavailable legacy agent must not silently switch');
  const select = {value: '', dataset: {savedAgent: 'chosen'}};
  const card = {querySelector: () => select};
  const button = {closest: () => card};
  requests.length = 0;
  await context.runBackgroundTaskAction('task', 'activate', button);
  assert.equal(requests[0].body.actionAgentId, '', 'empty selection must explicitly clear the saved agent');
  assert.ok(requests[0].url.endsWith('/edit'));
  assert.ok(requests[1].url.endsWith('/activate'));
  select.dataset.savedAgent = 'chosen';
  requests.length = 0;
  response = {code: 'ERROR', info: 'invalid agent'};
  await context.runBackgroundTaskAction('task', 'activate', button);
  assert.equal(requests.length, 1, 'failed edit must not activate the previous choice');
  assert.equal(button.disabled, false);
  assert.equal(alerts.length, 1);
  console.log('Background task UI: syntax and routing selection checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
