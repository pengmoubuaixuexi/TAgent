const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/eval.html'), 'utf8');
function screen(detail) {
    const nodes = new Map(), notices = [], downloads = [];
    const context = vm.createContext({
        state: {selectedRun: 'eval', currentJudge: {evalRunId: 'eval', judgeJobId: 'judge', results: [{resultId: 'r1'}]}},
        api: async () => detail,
        $: id => {
            if (!nodes.has(id)) nodes.set(id, {innerHTML: '', textContent: '', classList: {remove() {}}});
            return nodes.get(id);
        },
        esc: text => String(text ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;'),
        score: value => String(value),
        toast: (...args) => notices.push(args),
        downloadJsonFile: (...args) => downloads.push(args),
        judgeComparisonJson: value => value,
    });
    for (const name of ['openResult', 'downloadJudgeBatch']) {
        const source = html.split('\n').find(line => line.startsWith(`async function ${name}(`));
        assert.ok(source, `real page function ${name}`);
        vm.runInContext(source, context);
    }
    return {context, nodes, notices, downloads};
}

test('unreadable trace shows an escaped error while the answer remains readable', async () => {
    const ui = screen({stableKey: 'Q1', finalAnswer: 'saved answer', traceError: '破损 <script>bad</script>'});
    await ui.context.openResult('r1');
    const body = ui.nodes.get('detailBody').innerHTML;
    assert.match(body, /saved answer/);
    assert.match(body, /role="alert"/);
    assert.match(body, /&lt;script&gt;/);
    assert.doesNotMatch(body, /<script>/);
});

test('compatible historical trace is clearly marked and contains the restored evidence', async () => {
    const ui = screen({stableKey: 'Q1', trace: {steps: [{content: 'evidence'}], traceReadCompatibility: {sourceUnchanged: true}}});
    await ui.context.openResult('r1');
    const body = ui.nodes.get('detailBody').innerHTML;
    assert.match(body, /兼容读取/);
    assert.match(body, /evidence/);
    assert.doesNotMatch(body, /role="alert"/);
});

test('batch export does not download incomplete evidence after a trace read error', async () => {
    const ui = screen({traceError: '评测 Trace 解析失败'});
    await ui.context.downloadJudgeBatch();
    assert.equal(ui.downloads.length, 0);
    assert.equal(ui.context.state.currentJudge.results[0].executionTrace, undefined);
    assert.equal(ui.notices.at(-1)[0], '评测 Trace 解析失败');
    assert.equal(ui.notices.at(-1)[1], true);
});
