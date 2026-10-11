const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/index.html'), 'utf8');
const ledgerCode = html.slice(html.indexOf('function initializeRunEventLedger('),
  html.indexOf('function findToolCallEntry('));
const restoreCode = html.slice(html.indexOf('function restoreTimelineSnapshot('),
  html.indexOf('function activatePendingTimelineInteractions('));

function fixture() {
  const context = vm.createContext({
    state: {userId: 'alice', sessionId: 'session-1'},
    window: {},
    createRunTimelineView: aiMsg => ({aiMsg, rendered: '', events: []}),
    timelinePayload: record => record.data
  });
  vm.runInContext(ledgerCode + '\n' + restoreCode, context);
  // No browser is needed to exercise cursor coverage; the browser suite covers the DOM renderer.
  context.applyRunTimelineEvent = (view, type, payload, eventId) => {
    if (!context.acceptRunEvent(view.aiMsg, payload, eventId)) return;
    view.events.push(eventId);
    if (type === 'token') view.rendered += payload.token;
    view.lastEventId = view.aiMsg._runEventLedger.lastEventId;
  };
  const message = {dataset: {runId: 'run-1'}};
  const payload = {runId: 'run-1', sessionId: 'session-1'};
  const snapshot = {
    ...payload,
    lastEventId: '100-5',
    timelineEvents: [
      {eventId: '100-1', eventType: 'step_start', data: payload},
      {eventId: '100-4', eventType: 'token', data: {...payload, token: 'abc'}},
      {eventId: '100-5', eventType: 'step_end', data: payload}
    ]
  };
  return {context, message, payload, snapshot};
}

test('compacted snapshot absorbs old raw token IDs before plan-confirm replay', () => {
  const {context, message, payload, snapshot} = fixture();
  const view = context.restoreTimelineSnapshot(message, snapshot, '');
  for (const [eventId, token] of [['100-2', 'a'], ['100-3', 'b'], ['100-4', 'c']]) {
    context.applyRunTimelineEvent(view, 'token', {...payload, token}, eventId);
  }
  assert.equal(view.rendered, 'abc');
  assert.equal(view.events.length, 3, 'initial snapshot must render before the coverage boundary is installed');
  assert.equal(message._runEventLedger.snapshotCoveredThrough, '100-5');
  assert.equal(context.acceptRunEvent(message, payload, '100-6'), true);
  assert.equal(context.acceptRunEvent(message, payload, '100-6'), false);
});

test('ordinary live events and new post-snapshot events can arrive out of order', () => {
  const {context, message, payload, snapshot} = fixture();
  context.initializeRunEventLedger(message, payload.runId, payload.sessionId);
  assert.equal(context.acceptRunEvent(message, payload, '100-9'), true);
  assert.equal(context.acceptRunEvent(message, payload, '100-7'), true);
  assert.equal(message._runEventLedger.lastEventId, '100-9');
  context.restoreTimelineSnapshot(message, snapshot, '');
  assert.equal(context.acceptRunEvent(message, payload, '100-9'), true);
  assert.equal(context.acceptRunEvent(message, payload, '100-7'), true);
  assert.equal(message._runEventLedger.lastEventId, '100-9');
  assert.equal(message._runEventLedger.snapshotCoveredThrough, '100-5');
});

test('coverage survives saved plan-review state without leaking to another scope', () => {
  const {context, message, payload, snapshot} = fixture();
  context.restoreTimelineSnapshot(message, snapshot, '');
  const seed = context.serializeRunEventLedger(message);
  const copy = {dataset: {runId: payload.runId}};
  context.initializeRunEventLedger(copy, payload.runId, payload.sessionId, seed);
  assert.equal(context.acceptRunEvent(copy, payload, '100-2'), false);
  for (const [userId, runId, sessionId] of [
    ['bob', payload.runId, payload.sessionId],
    ['alice', 'other-run', payload.sessionId],
    ['alice', payload.runId, 'other-session']
  ]) {
    context.state.userId = userId;
    const other = {dataset: {runId}};
    context.initializeRunEventLedger(other, runId, sessionId, seed);
    assert.equal(context.acceptRunEvent(other, {runId, sessionId}, '100-2'), true);
    assert.equal(other._runEventLedger.snapshotCoveredThrough, null);
  }
});

test('legacy and transient events retain exact-ID semantics', () => {
  const {context, message, payload, snapshot} = fixture();
  context.restoreTimelineSnapshot(message, snapshot, '');
  assert.equal(context.acceptRunEvent(message, payload, ''), true);
  assert.equal(context.acceptRunEvent(message, payload, ''), true);
  assert.equal(context.acceptRunEvent(message, payload, 'local-2'), true);
  assert.equal(context.acceptRunEvent(message, payload, 'local-2'), false);
  assert.equal(context.acceptRunEvent(message, payload, 'local-1'), true);
  assert.equal(context.acceptRunEvent(message, {...payload, runId: 'foreign'}, ''), false);
  assert.equal(context.acceptRunEvent(message, {...payload, sessionId: 'foreign'}, '100-10'), false);
});

test('snapshot cursor is retained even when it exceeds the last compact record', () => {
  const {context, message, payload, snapshot} = fixture();
  snapshot.lastEventId = '100-8';
  const view = context.restoreTimelineSnapshot(message, snapshot, '');
  assert.equal(view.lastEventId, '100-8');
  assert.equal(message._runEventLedger.lastEventId, '100-8');
  assert.equal(context.acceptRunEvent(message, payload, '100-7'), false);
  assert.equal(context.acceptRunEvent(message, payload, '100-9'), true);
});

test('missing or older snapshot cursor cannot move behind rendered events', () => {
  for (const cursor of [undefined, '100-2']) {
    const {context, message, payload, snapshot} = fixture();
    snapshot.lastEventId = cursor;
    const view = context.restoreTimelineSnapshot(message, snapshot, '');
    assert.equal(view.lastEventId, '100-5');
    assert.equal(context.acceptRunEvent(message, payload, '100-3'), false);
  }
});

test('Redis Stream IDs compare numerically without Number precision loss', () => {
  const {context} = fixture();
  assert.equal(context.compareRunEventIds('100-10', '100-9'), 1);
  assert.equal(context.compareRunEventIds('99-99', '100-0'), -1);
  assert.equal(context.compareRunEventIds('9007199254740993-0', '9007199254740992-99'), 1);
  assert.equal(context.compareRunEventIds('100-5', '100-5'), 0);
  assert.equal(context.compareRunEventIds('custom-5', '100-5'), null);
});
