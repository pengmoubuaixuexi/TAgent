const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/eval.html'), 'utf8');
const pageScript = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];
// Execute the page's real declarations and handlers, without authentication or automatic page startup.
const source = pageScript.slice(pageScript.indexOf("const API=")).replace(/\binit\(\);\s*$/, '');
const model = (apiId, overrides = {}) => ({modelName: 'deepseek-chat', apiId, apiName: `连接 ${apiId.slice(-1)}`, source: 'PLATFORM', ...overrides});

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
}

function node(id) {
    const classes = new Set(['judgeModal', 'judgeModelRetry'].includes(id) ? ['hidden'] : []);
    let markup = '', value = ({judgeScope: 'ALL', judgeThreshold: '0.8', judgeConcurrency: '3'})[id] || '';
    return {
        id, textContent: '', disabled: false,
        classList: {
            add: name => classes.add(name), remove: name => classes.delete(name), contains: name => classes.has(name),
            toggle(name, force) { const add = force ?? !classes.has(name); add ? classes.add(name) : classes.delete(name); },
        },
        get innerHTML() { return markup; },
        set innerHTML(text) { markup = text; if (id === 'judgeModel') value = [...text.matchAll(/<option value="([^"]*)"/g)][0]?.[1] || ''; },
        get value() { return value; },
        set value(text) { value = id !== 'judgeModel' || [...markup.matchAll(/<option value="([^"]*)"/g)].some(match => match[1] === text) ? text : ''; },
    };
}

function screen(handler) {
    const nodes = new Map(), calls = [], notices = [];
    const get = id => { if (!nodes.has(id)) nodes.set(id, node(id)); return nodes.get(id); };
    const context = vm.createContext({
        document: {getElementById: get, addEventListener() {}},
        clearInterval() {},
        fetch: async (url, options = {}) => {
            const route = url.replace('/api/v1/eval', '');
            calls.push({route, options});
            const data = await handler(route, options);
            return {ok: true, json: async () => ({code: '0000', data})};
        },
    });
    vm.runInContext(source, context);
    context.captureNotice = (...args) => notices.push(args);
    vm.runInContext("toast=captureNotice;renderRunSidebar=()=>{};renderRunWelcome=()=>{};renderRunDetail=()=>{};managePolling=()=>{};state.selectedRun='run-a';state.run={evalRunId:'run-a'};", context);
    const state = vm.runInContext('state', context);
    const choose = index => { get('judgeModel').value = String(index); context.selectJudgeModel(); };
    return {context, state, get, calls, notices, choose, posts: () => calls.filter(call => call.options.method === 'POST')};
}

function withRunDetails(handler) {
    return (route, options) => {
        if (route.endsWith('/judge-jobs')) return [];
        if (/^\/runs\/[^/]+$/.test(route)) return {evalRunId: route.split('/').at(-1)};
        return handler(route, options);
    };
}

test('loading blocks submission, choices show safe names and sources, and DeepSeek uses the selected connection', async () => {
    const loading = deferred();
    const ui = screen(withRunDetails((route, options) => options.method === 'POST' ? {} : loading.promise));
    const opening = ui.context.openJudgeModal();
    assert.equal(ui.calls[0].route, '/runs/run-a/judge-models');
    assert.equal(ui.get('judgeModel').disabled, true);
    assert.equal(ui.get('judgeSubmit').disabled, true);
    await ui.context.createJudge();
    assert.equal(ui.posts().length, 0);
    loading.resolve([
        model('internal-api-one', {modelName: 'qwen-plus', apiName: '平台入口'}),
        model('internal-api-two', {apiName: '独立 DeepSeek', source: 'BYOK'}),
        model('internal-api-three', {apiName: '<img src=x onerror=alert(1)>', source: 'OWNED'}),
    ]);
    await opening;
    assert.equal(ui.get('judgeModel').value, '');
    assert.equal(ui.get('judgeSubmit').disabled, true);
    assert.match(ui.get('judgeModel').innerHTML, /deepseek-chat · 独立 DeepSeek · 个人连接（BYOK）/);
    assert.match(ui.get('judgeModel').innerHTML, /平台入口 · 平台/);
    assert.match(ui.get('judgeModel').innerHTML, /&lt;img src=x onerror=alert\(1\)&gt; · 自有/);
    assert.doesNotMatch(ui.get('judgeModel').innerHTML, /internal-api|<img/);
    await ui.context.createJudge();
    assert.equal(ui.posts().length, 0);
    ui.choose(1);
    assert.equal(ui.get('judgeSubmit').disabled, false);
    await ui.context.createJudge();
    assert.equal(ui.posts().length, 1);
    assert.equal(ui.posts()[0].route, '/runs/run-a/judge');
    assert.deepEqual(JSON.parse(ui.posts()[0].options.body), {scopeType: 'ALL', threshold: 0.8, modelId: 'deepseek-chat', apiId: 'internal-api-two', concurrency: 3});
});

test('reopening restores only the exact previous model and connection, even after reordering', async () => {
    let choices = [model('api-a'), model('api-b')];
    const ui = screen(() => choices);
    await ui.context.openJudgeModal();
    ui.choose(1);
    ui.context.closeModal('judgeModal');
    choices = [model('api-b'), model('api-a')];
    await ui.context.openJudgeModal();
    assert.equal(ui.get('judgeModel').value, '0');
    assert.equal(ui.get('judgeSubmit').disabled, false);
    ui.context.closeModal('judgeModal');
    choices = [model('api-a')];
    await ui.context.openJudgeModal();
    assert.equal(ui.get('judgeModel').value, '');
    assert.equal(ui.get('judgeSubmit').disabled, true);
    assert.match(ui.get('judgeModelStatus').textContent, /上次选择.*不可用/);
    await ui.context.createJudge();
    assert.equal(ui.posts().length, 0);
});

for (const [label, response] of [['empty', []], ['failure', new Error('服务暂不可用 <script>')], ['invalid payload', {}]]) {
    test(`${label} clears stale choices, disables submission, and can recover through retry`, async () => {
        let result = [model('api-a')];
        const ui = screen(() => { if (result instanceof Error) throw result; return result; });
        await ui.context.openJudgeModal();
        ui.choose(0);
        result = response;
        await ui.context.loadJudgeModels();
        assert.equal(ui.get('judgeModel').disabled, true);
        assert.equal(ui.get('judgeSubmit').disabled, true);
        assert.equal(ui.get('judgeModel').value, '');
        assert.equal(ui.get('judgeModelRetry').classList.contains('hidden'), false);
        assert.match(ui.get('judgeModelStatus').textContent, label === 'empty' ? /暂无可用/ : /加载失败/);
        if (label === 'empty') assert.match(ui.get('judgeModelStatus').textContent, /平台辅助服务设置/);
        await ui.context.createJudge();
        assert.equal(ui.posts().length, 0);
        result = [model('api-a')];
        await ui.context.loadJudgeModels();
        assert.equal(ui.get('judgeModel').value, '0');
        assert.equal(ui.get('judgeSubmit').disabled, false);
    });
}

for (const failOldRequest of [false, true]) {
    test(`switching runs ignores an old model request ${failOldRequest ? 'failure' : 'response'}`, async () => {
        const old = deferred();
        const ui = screen(withRunDetails(route => route === '/runs/run-a/judge-models' ? old.promise : [model('api-b', {modelName: 'new-model'})]));
        const opening = ui.context.openJudgeModal();
        await ui.context.selectRun('run-b');
        assert.equal(ui.get('judgeModal').classList.contains('hidden'), true);
        await ui.context.openJudgeModal();
        ui.choose(0);
        failOldRequest ? old.reject(new Error('old error')) : old.resolve([model('api-a', {modelName: 'old-model'})]);
        await opening;
        assert.match(ui.get('judgeModel').innerHTML, /new-model/);
        assert.doesNotMatch(ui.get('judgeModel').innerHTML, /old-model/);
        assert.equal(ui.get('judgeSubmit').disabled, false);
        assert.equal(ui.notices.length, 0);
    });
}

test('closing and reopening the same run ignores the previous model response', async () => {
    const old = deferred();
    let requests = 0;
    const ui = screen(() => ++requests === 1 ? old.promise : [model('api-b', {modelName: 'latest-model'})]);
    const first = ui.context.openJudgeModal();
    ui.context.closeModal('judgeModal');
    await ui.context.openJudgeModal();
    old.resolve([model('api-a', {modelName: 'stale-model'})]);
    await first;
    assert.match(ui.get('judgeModel').innerHTML, /latest-model/);
    assert.doesNotMatch(ui.get('judgeModel').innerHTML, /stale-model/);
});

test('double submit and reopening a pending submission cannot create duplicate Judge jobs', async () => {
    const submitted = deferred();
    const ui = screen(withRunDetails((route, options) => options.method === 'POST' ? submitted.promise : [model('api-a')]));
    await ui.context.openJudgeModal();
    ui.choose(0);
    const first = ui.context.createJudge();
    assert.equal(ui.get('judgeSubmit').disabled, true);
    assert.equal(ui.get('judgeModel').disabled, true);
    await ui.context.createJudge();
    ui.context.closeModal('judgeModal');
    await ui.context.openJudgeModal();
    assert.equal(ui.get('judgeSubmit').disabled, true);
    await ui.context.createJudge();
    assert.equal(ui.posts().length, 1);
    submitted.resolve({});
    await first;
    assert.equal(ui.posts().length, 1);
});

test('a pending submission stays bound to its original run and cannot close or refresh a different run', async () => {
    const submitted = deferred();
    const ui = screen(withRunDetails((route, options) => options.method === 'POST' ? submitted.promise : [model('api-a')]));
    await ui.context.openJudgeModal();
    ui.choose(0);
    const first = ui.context.createJudge();
    await ui.context.selectRun('run-b');
    await ui.context.openJudgeModal();
    const before = ui.calls.length;
    submitted.resolve({});
    await first;
    assert.equal(ui.posts()[0].route, '/runs/run-a/judge');
    assert.equal(ui.state.selectedRun, 'run-b');
    assert.equal(ui.get('judgeModal').classList.contains('hidden'), false);
    assert.equal(ui.get('judgeSubmit').disabled, false);
    assert.equal(ui.calls.length, before);
    assert.equal(ui.notices.length, 0);
});

test('a failed submission retains its explicit choice and permits an intentional retry', async () => {
    let failed = true;
    const ui = screen(withRunDetails((route, options) => {
        if (options.method !== 'POST') return [model('api-a')];
        if (failed) throw new Error('连接已禁用');
        return {};
    }));
    await ui.context.openJudgeModal();
    ui.choose(0);
    await ui.context.createJudge();
    assert.equal(ui.get('judgeSubmit').disabled, false);
    assert.equal(ui.get('judgeModal').classList.contains('hidden'), false);
    assert.equal(ui.get('judgeModel').value, '0');
    assert.deepEqual(ui.notices.at(-1), ['连接已禁用', true]);
    failed = false;
    await ui.context.createJudge();
    assert.equal(ui.posts().length, 2);
    assert.equal(ui.get('judgeModal').classList.contains('hidden'), true);
});

test('returning home invalidates model loading and direct stale submission', async () => {
    const loading = deferred();
    const ui = screen(() => loading.promise);
    const opening = ui.context.openJudgeModal();
    ui.context.backToRunHome();
    loading.resolve([model('api-a')]);
    await opening;
    ui.choose(0);
    await ui.context.createJudge();
    assert.equal(ui.get('judgeModal').classList.contains('hidden'), true);
    assert.equal(ui.get('judgeSubmit').disabled, true);
    assert.equal(ui.posts().length, 0);
});

test('out-of-order run details cannot reopen Judge with the wrong evaluation', async () => {
    const old = deferred();
    const ui = screen((route) => route === '/runs/run-a' ? old.promise : route.endsWith('/judge-jobs') ? [] : {evalRunId: 'run-b'});
    const first = ui.context.selectRun('run-a');
    await ui.context.selectRun('run-b');
    old.resolve({evalRunId: 'run-a'});
    await first;
    assert.equal(ui.state.selectedRun, 'run-b');
    assert.equal(ui.state.run.evalRunId, 'run-b');
    assert.equal(ui.calls.filter(call => call.route === '/runs/run-a/judge-jobs').length, 0);
});
