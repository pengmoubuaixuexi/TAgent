const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const source = html.slice(html.indexOf('// 限定前端状态更新所属于的当前运行'), html.indexOf('// DAG 并行下多个步骤同时发起审批'));
function fixture(fetch, timers = {setTimeout, clearTimeout}) {
  const elements = {};
  for (const id of ['interveneBar', 'answerNowBtn', 'steerBtn', 'interveneHint', 'interveneStatus', 'msgInput']) {
    elements[id] = { disabled: false, textContent: '', className: '', title: '', value: '', style: {}, classList: {toggle() {}}, focus() {} };
  }
  const state = {sessionId:'s1', activeRunId:'r1', runUiGeneration:1, sending:true, interventionEnabled:true, finalizing:false, flowStep4Active:false};
  const ctx = vm.createContext({state, document:{getElementById:id=>elements[id]}, API:'/api/v1/agent', fetch, AbortController, setTimeout:timers.setTimeout, clearTimeout:timers.clearTimeout});
  vm.runInContext(source, ctx);
  ctx.showAnswerNow(true);
  return {ctx, state, elements};
}
const response = (status, body) => ({ok: status >= 200 && status < 300, status, json: async () => body});
const ok = () => response(200, {code:'0000',data:true});
const deferred = () => {let resolve;const promise = new Promise(r=>resolve=r);return {promise,resolve};};

for (const [label, fetch] of [
  ['HTTP error', async()=>response(503,{info:'暂不可用'})],
  ['business error', async()=>response(200,{code:'0001',info:'无权限',data:false})],
  ['false data', async()=>response(200,{code:'0000',info:'未启用',data:false})],
  ['non-JSON', async()=>({ok:true,status:200,json:async()=>{throw Error('invalid')}})],
  ['network error', async()=>{throw Error('network');}]
]) {
  test(`${label} leaves steer input and answer-now retryable`, async()=>{
    let f=fixture(fetch); f.elements.msgInput.value='新的要求'; await f.ctx.steer();
    assert.equal(f.elements.msgInput.value,'新的要求');
    assert.equal(f.elements.steerBtn.disabled,false);
    assert.match(f.elements.interveneStatus.textContent,/未确认/);
    f=fixture(fetch); await f.ctx.answerNow();
    assert.equal(f.state.finalizing,false);
    assert.equal(f.elements.answerNowBtn.disabled,false);
    assert.equal(f.elements.steerBtn.disabled,false);
    assert.match(f.elements.interveneStatus.textContent,/未确认/);
  });
}
test('success validates payload and acknowledges request without claiming effect',async()=>{
  let posted; const f=fixture(async(url,options)=>{posted={url,body:JSON.parse(options.body)};return ok();});
  f.elements.msgInput.value='  新条件  '; await f.ctx.steer();
  assert.equal(posted.url,'/api/v1/agent/steer'); assert.equal(posted.body.idea,'新条件');
  assert.equal(f.elements.msgInput.value,''); assert.match(f.elements.interveneStatus.textContent,/请求已提交/);
  await f.ctx.answerNow(); assert.equal(posted.url,'/api/v1/agent/answer_now');
  assert.equal(f.state.finalizing,true); assert.equal(f.elements.answerNowBtn.disabled,true);
  assert.equal(f.elements.steerBtn.disabled,true);
});
test('preserves text edited while request is pending and blocks duplicate sends',async()=>{
  const d=deferred();let calls=0; const f=fixture(async()=>{calls++;return d.promise;});
  f.elements.msgInput.value='first'; const pending=f.ctx.steer();
  f.elements.msgInput.value='new text'; await f.ctx.steer(); await f.ctx.answerNow();
  assert.equal(calls,1); d.resolve(ok()); await pending;
  assert.equal(f.elements.msgInput.value,'new text'); assert.equal(f.elements.steerBtn.disabled,false);
});
test('a stale response must not alter a later run or its input',async()=>{
  const d=deferred(); const f=fixture(async()=>d.promise);
  f.elements.msgInput.value='first'; const pending=f.ctx.answerNow();
  f.state.sessionId='s2'; f.state.activeRunId='r2'; f.state.runUiGeneration++; f.state.finalizing=false;
  f.ctx.showAnswerNow(true); f.elements.msgInput.value='new run';
  d.resolve(response(200,{code:'0001',data:false,info:'old error'})); await pending;
  assert.equal(f.state.finalizing,false); assert.equal(f.elements.answerNowBtn.disabled,false);
  assert.equal(f.elements.msgInput.value,'new run'); assert.doesNotMatch(f.elements.interveneStatus.textContent,/old error/);
});

for (const stage of ['fetch', 'body']) {
  test(`${stage} timeout aborts and reports an unconfirmed result`, async()=>{
    let fireTimeout, signal, cleared=false;
    const timers={setTimeout:(fn,ms)=>{assert.equal(ms,15000);fireTimeout=fn;return 1;},clearTimeout:()=>{cleared=true;}};
    const f=fixture(async(url,options)=>{
      signal=options.signal;
      if (stage === 'fetch') return new Promise(()=>{});
      return {ok:true,status:200,json:()=>new Promise(()=>{})};
    },timers);
    f.elements.msgInput.value='保留这条引导';
    const pending=f.ctx.steer();
    await Promise.resolve(); await Promise.resolve();
    fireTimeout(); await pending;
    assert.equal(signal.aborted,true); assert.equal(cleared,true);
    assert.equal(f.elements.msgInput.value,'保留这条引导');
    assert.match(f.elements.interveneStatus.textContent,/未确认.*超时/);
    assert.equal(f.elements.steerBtn.disabled,false);
  });
}
test('an old request timing out does not change a later run',async()=>{
  let fireTimeout; const timers={setTimeout:fn=>{fireTimeout=fn;return 1;},clearTimeout:()=>{}};
  const f=fixture(()=>new Promise(()=>{}),timers);
  const pending=f.ctx.answerNow();
  f.state.sessionId='s2'; f.state.activeRunId='r2'; f.state.runUiGeneration++; f.state.finalizing=false;
  f.ctx.showAnswerNow(true); f.elements.msgInput.value='新的运行';
  fireTimeout(); await pending;
  assert.equal(f.state.finalizing,false); assert.equal(f.elements.answerNowBtn.disabled,false);
  assert.equal(f.elements.msgInput.value,'新的运行');
  assert.doesNotMatch(f.elements.interveneStatus.textContent,/超时/);
});
