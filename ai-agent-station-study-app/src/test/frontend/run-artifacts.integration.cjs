const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), http = require('node:http'), os = require('node:os');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
const markedFile = path.join(path.dirname(require.resolve('marked')), 'marked.umd.js');
const screenshots = fs.mkdtempSync(path.join(os.tmpdir(), 'tagent-run-artifacts-'));
const endpoint = '/api/v1/workspace/outcomes/artifacts', requests = [];
const documentArtifact = {artifactId: 'artifact-document', runId: 'run-a', title: '<img src=x onerror=window.xss=1>旅行攻略', format: 'MARKDOWN', revision: 2, currentRevision: 2,
  content: {markdown: '# 北京行程\n\n- 故宫\n- 颐和园\n\n<script>window.xss=1</script><img src=x onerror="window.xss=1"><svg onload="window.xss=1"></svg><iframe src="https://evil.test"></iframe>\n\n[危险](javascript:alert(1)) [来源](https://example.test/source)\n\n<div onclick="window.xss=1"><strong style="background:url(https://evil.test)">保留文字</strong></div>'}};
const tableArtifact = {artifactId: 'artifact-table', runId: 'run-a', title: '行程预算', format: 'TABLE', revision: 1, currentRevision: 1,
  content: {columns: ['日期', '地点', '费用'], rows: Array.from({length: 105}, (_, i) => [String(i + 1), i ? '景点' : '<img src=x onerror=window.xss=1>\n第二行', '=1+1'])}};
const codeArtifact = {artifactId: 'artifact-code', runId: 'run-a', title: '网页源码', format: 'CODE', filename: 'index.html', language: 'html', revision: 1, currentRevision: 1,
  content: {filename: 'index.html', language: 'html', code: '<!doctype html>\r\n<script>window.codeExecuted=1</script>\r\n<img src="https://evil.test/image" onerror="window.codeExecuted=1">\r\n  <p>保留缩进</p>\r\n'}};
const saved = new Map([[documentArtifact.artifactId, documentArtifact], [tableArtifact.artifactId, tableArtifact], [codeArtifact.artifactId, codeArtifact]]);
let listed = [], wrongPreview = null, artifactStream = null;
const frame=(id,type,data)=>`id: ${id}\nevent: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
const metadata = row => {const {content, ...rest} = row; return rest;};
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost'); requests.push(url.pathname + url.search);
  const send = (data, status = 200) => {res.writeHead(status, {'Content-Type': 'application/json'}); res.end(JSON.stringify({code: status === 200 ? '0000' : String(status), data}));};
  if (url.pathname === '/fixture.html') {
    res.writeHead(200, {'Content-Type': 'text/html'}); return res.end('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/run-artifacts.css"><style>body{margin:0;padding:12px;background:#0b1321;color:white;font:14px system-ui;box-sizing:border-box}.message{max-width:800px;min-width:0;margin:0 auto}</style><script src="/test-marked.js"></script><script src="/run-artifacts.js"></script><div class="message" id="message" data-run-id="run-a"><div class="msg-content"></div></div>');
  }
  if (url.pathname === '/test-marked.js') { res.writeHead(200, {'Content-Type': 'application/javascript'}); return res.end(fs.readFileSync(markedFile)); }
  if (url.pathname === '/api/v1/auth/me') return send({userId: 'alice', username: 'Alice', role: 'USER'});
  if (url.pathname === '/api/v1/auth/csrf') {res.writeHead(200, {'Content-Type': 'application/json'}); return res.end(JSON.stringify({headerName: 'X-CSRF-TOKEN', token: 'fixture'}));}
  if (url.pathname.endsWith('/query_available_agents')) return send([{agentId: 'a1', agentName: 'Flow', strategy: 'flow', status: 1}]);
  if(url.pathname.endsWith('/auto_agent')){
    let body='';req.on('data',chunk=>body+=chunk);req.on('end',()=>{
      const {runId,sessionId}=JSON.parse(body);artifactStream={res,runId,sessionId};
      res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
      res.write(frame('artifact-live-1','ack',{runId,sessionId}));
      res.write(frame('artifact-live-2','artifact_progress',{runId,sessionId,phase:'generating'}));
    });return;
  }
  if (url.pathname === endpoint) return send(listed.map(metadata));
  if (url.pathname.startsWith(endpoint + '/')) {
    const id = decodeURIComponent(url.pathname.slice(endpoint.length + 1));
    if (id.endsWith('/revisions')) {const row = saved.get(id.slice(0, -10)); return send(row ? [metadata(row)] : []);}
    return send(wrongPreview || saved.get(id), saved.has(id) || wrongPreview ? 200 : 404);
  }
  if (url.pathname === '/api/v1/history') return send([{runId: 'run-a', question: '北京行程', answer: '已生成攻略', status: 'COMPLETED'}]);
  if (url.pathname === '/api/v1/history/run-a') return send({runId: 'run-a', question: '北京行程', answer: '已生成攻略', status: 'COMPLETED'});
  if (url.pathname.endsWith('/runs/run-a/feedback')) return send({});
  if (url.pathname.startsWith('/api/')) return send([]);
  const file = path.resolve(root, '.' + url.pathname);
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file)) {res.writeHead(404); return res.end();}
  res.writeHead(200, {'Content-Type': file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html'}); res.end(fs.readFileSync(file));
});

(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const page = await browser.newPage({viewport: {width: 1280, height: 900}}), errors = [], external = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/*', route => {
    if (route.request().url().startsWith(origin)) return route.continue();
    external.push(route.request().url()); return route.abort();
  });
  try {
    await page.goto(origin + '/fixture.html');
    const render = value => page.evaluate(value => RunArtifacts.render(document.getElementById('message'), value), value);
    const progress = value => page.evaluate(value => RunArtifacts.progress(document.getElementById('message'), value), value);
    await progress({runId:'other-run', phase:'generating'});assert.equal(await page.locator('.run-artifact-progress').count(),0);
    await progress({runId:'run-a',phase:'generating'});await progress({runId:'run-a',phase:'repairing',attempt:1,message:'<img src=x onerror=window.xss=1>'});
    assert.equal(await page.locator('.run-artifact-progress').count(),1,'progress replaces status rather than duplicating it');
    assert((await page.locator('.run-artifact-progress').textContent()).includes('正在修复'));
    assert.equal(await page.locator('.run-artifact-progress img').count(),0);
    await progress({runId:'run-a',phase:'saving'});assert((await page.locator('.run-artifact-progress').textContent()).includes('正在保存'));
    await render({...metadata(documentArtifact), downloadUrl: 'https://evil.test/download', editUrl: 'javascript:alert(1)'});
    assert.equal(await page.locator('.run-artifact-progress').count(),0,'saved artifact clears pending progress');
    await render(metadata(documentArtifact)); await render({...metadata(documentArtifact), runId: 'other-run'});
    assert.equal(await page.locator('.run-artifact-card').count(), 1, 'duplicate event and wrong run do not create additional cards');
    assert.equal(await page.locator('.run-artifact-card h4 img').count(), 0);
    assert.equal(await page.getByRole('link', {name: '下载 Markdown'}).getAttribute('href'), endpoint + '/artifact-document/export?revision=2');
    assert.equal(await page.getByRole('link', {name: '查看与编辑'}).getAttribute('href'), '/outcomes.html?runId=run-a&artifactId=artifact-document&revision=2');
    await page.getByRole('button', {name: '预览', exact: true}).click();
    await page.waitForSelector('.run-artifact-preview[data-loaded=true]');
    assert.equal(await page.locator('.run-artifact-preview h1').textContent(), '北京行程');
    assert.equal(await page.locator('.run-artifact-preview img,.run-artifact-preview script,.run-artifact-preview svg,.run-artifact-preview iframe,.run-artifact-preview [onclick],.run-artifact-preview [style]').count(), 0);
    assert.equal(await page.locator('.run-artifact-preview a[href^="javascript:"]').count(), 0);
    assert.equal(await page.getByRole('link', {name: '来源'}).getAttribute('rel'), 'noopener noreferrer');
    assert.equal(await page.evaluate(() => window.xss), undefined);
    assert(requests.includes(endpoint + '/artifact-document?revision=2'), 'preview reads requested persisted version');
    assert.deepEqual(external, [], 'untrusted preview markup does not send requests');
    await render(metadata(tableArtifact));
    await page.getByRole('button', {name: '预览', exact: true}).click();
    await page.waitForFunction(() => document.querySelectorAll('.run-artifact-preview[data-loaded=true]').length === 2);
    assert.equal(await page.locator('.run-artifact-preview table tr').count(), 101, 'table caps preview at 100 rows');
    assert.equal(await page.locator('.run-artifact-preview table img').count(), 0);
    assert.equal(await page.locator('.run-artifact-preview table td').nth(1).textContent(), '<img src=x onerror=window.xss=1>\n第二行');
    assert.equal(await page.getByRole('link', {name: '下载 CSV'}).getAttribute('href'), endpoint + '/artifact-table/export?revision=1');
    assert((await page.locator('.run-artifact-preview').last().textContent()).includes('下载包含全部数据'));
    for (const width of [320, 768, 1280]) {
      await page.setViewportSize({width, height: 900});
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'artifact cards overflow at ' + width);
      if (width === 320) await page.screenshot({path: path.join(screenshots, 'mobile.png'), fullPage: true});
    }
    await page.screenshot({path: path.join(screenshots, 'desktop.png'), fullPage: true});
    await render({...metadata(documentArtifact), artifactId: 'invalid-zero', revision: 0});
    await render({...metadata(documentArtifact), artifactId: 'invalid-negative', revision: -1});
    await render({...metadata(documentArtifact), artifactId: 'invalid-string', revision: 'abc'});
    assert.equal(await page.locator('.run-artifact-card').count(), 2, 'invalid explicit revisions rejected');
    await render(metadata(codeArtifact));
    const codeCard=page.locator('.run-artifact-card').last();
    assert((await codeCard.textContent()).includes('index.html · html'));
    assert.equal(await codeCard.getByRole('link',{name:'下载 index.html',exact:true}).getAttribute('href'),endpoint+'/artifact-code/export?revision=1');
    await codeCard.getByRole('button',{name:'预览',exact:true}).click();
    await page.waitForSelector('.run-artifact-source code');
    assert.equal(await page.locator('.run-artifact-source code').textContent(),codeArtifact.content.code,'source preview preserves source text and CRLF');
    assert.equal(await page.locator('.run-artifact-source script,.run-artifact-source img').count(),0);
    assert.equal(await page.evaluate(()=>window.codeExecuted),undefined,'HTML source is not executed');assert.deepEqual(external,[]);
    await progress({runId:'run-a',phase:'failed'});assert((await page.locator('.run-artifact-progress').textContent()).includes('未能保存'));
    await render(metadata(codeArtifact));assert.equal(await page.locator('.run-artifact-progress').count(),0,'saved event replay also clears obsolete progress');
    for (const mismatch of ['runId', 'artifactId', 'revision']) {
      await page.reload(); await render(metadata(documentArtifact));
      wrongPreview = {...documentArtifact, [mismatch]: mismatch === 'revision' ? 3 : 'wrong-identity'};
      await page.getByRole('button', {name: '预览', exact: true}).click();
      await page.waitForFunction(() => !document.querySelector('.run-artifact-preview').textContent.includes('正在读取'));
      assert.equal(await page.locator('.run-artifact-preview [data-loaded=true],.run-artifact-preview h1').count(), 0, mismatch + ': mismatched response is not previewed');
      assert.equal(await page.locator('.run-artifact-preview').getAttribute('data-loaded'), null);
    }
    wrongPreview = null; listed = [documentArtifact, tableArtifact, {...documentArtifact, artifactId: 'other-owner-run', runId: 'other-run'}];
    await page.reload(); await page.evaluate(() => Promise.all([RunArtifacts.refresh(document.getElementById('message')), RunArtifacts.refresh(document.getElementById('message'))]));
    assert.equal(await page.locator('.run-artifact-card').count(), 2, 'history restore reloads valid run artifacts only');
    await page.evaluate(() => RunArtifacts.refresh(document.getElementById('message')));
    assert.equal(await page.locator('.run-artifact-card').count(), 2, 'refresh is idempotent');
    await progress({runId:'run-a',phase:'failed'});
    await page.evaluate(() => RunArtifacts.refresh(document.getElementById('message')));
    assert.equal(await page.locator('.run-artifact-progress[data-phase=failed]').count(),1,'older saved artifacts do not erase a later failure on refresh');
    await page.reload();await progress({runId:'run-a',phase:'failed'});await page.evaluate(() => RunArtifacts.refresh(document.getElementById('message')));
    assert.equal(await page.locator('.run-artifact-progress[data-phase=failed]').count(),1,'loading prior saved cards for the first time does not erase a failure');
    await page.reload();await progress({runId:'run-a',phase:'saving'});await page.evaluate(() => RunArtifacts.refresh(document.getElementById('message')));
    assert.equal(await page.locator('.run-artifact-progress').count(),0,'newly recovered artifact confirms a pending save when created event was lost');
    assert.deepEqual(errors, []);
    listed = [documentArtifact, tableArtifact];
    await page.goto(origin + '/outcomes.html?runId=run-a&artifactId=artifact-table&revision=1');
    await page.waitForFunction(() => !document.getElementById('reload').disabled && !document.getElementById('artifact-form').hidden);
    assert.equal(await page.inputValue('#artifact-title'), tableArtifact.title);
    assert.equal(await page.inputValue('#revision-select'), '1');
    assert.equal(await page.isVisible('#table-editor'), true);
    assert.equal(await page.getAttribute('#export-artifact', 'href'), endpoint + '/artifact-table/export?revision=1');
    assert.equal(await page.locator('#table-preview img').count(), 0);
    await page.goto(origin + '/outcomes.html?runId=run-a&artifactId=artifact-document&revision=2');
    await page.waitForFunction(() => !document.getElementById('reload').disabled && !document.getElementById('artifact-form').hidden);
    assert.equal(await page.inputValue('#artifact-markdown'), documentArtifact.content.markdown);
    assert.equal(await page.inputValue('#revision-select'), '2');
    wrongPreview = {...documentArtifact, runId: 'other-run'};
    await page.goto(origin + '/outcomes.html?runId=run-a&artifactId=artifact-document&revision=2');
    await page.waitForFunction(() => !document.getElementById('reload').disabled);
    assert.equal(await page.isHidden('#artifact-form'), true, 'foreign-run editor deep link rejected');
    assert((await page.textContent('#notice')).includes('不属于'));
    wrongPreview = null;
    // Exercise the real chat event dispatcher as well as the standalone card component.
    await page.goto(origin + '/index.html'); await page.waitForFunction(() => document.querySelectorAll('#agentSelect option').length === 2);
    const eventResult = await page.evaluate(a => {
      const message = addMessage('assistant', '', false, null, 'run-a');
      const view = restoreTimelineSnapshot(message, {runId: 'run-a', sessionId: state.sessionId, timelineEvents: []}, '');
      applyRunTimelineEvent(view, 'artifact_created', {...a, sessionId: state.sessionId}, 'artifact-event-1', true);
      applyRunTimelineEvent(view, 'artifact_created', {...a, sessionId: state.sessionId}, 'artifact-event-1', true);
      applyRunTimelineEvent(view, 'artifact_created', {...a, runId: 'other-run', sessionId: state.sessionId}, 'artifact-event-2', true);
      return message.querySelectorAll('.run-artifact-card').length;
    }, metadata(documentArtifact));
    assert.equal(eventResult, 1, 'real timeline artifact_created replay deduplicates and rejects foreign run');
    // Drive the real sendMessage SSE path. A later file may fail after another was saved.
    listed=[];
    await page.evaluate(()=>{state.planReviewEnabled=false;state.currentAgentId='a1';document.getElementById('msgInput').value='生成代码文件';window.artifactFixtureRequest=sendMessage();});
    await page.waitForSelector('.run-artifact-progress[data-phase=generating]');
    const emit=(id,type,data)=>artifactStream.res.write(frame(id,type,{runId:artifactStream.runId,sessionId:artifactStream.sessionId,...data}));
    emit('artifact-live-3','artifact_progress',{phase:'repairing',attempt:1});
    await page.waitForSelector('.run-artifact-progress[data-phase=repairing]');
    emit('artifact-live-4','artifact_progress',{phase:'saving'});
    await page.waitForSelector('.run-artifact-progress[data-phase=saving]');
    const liveArtifact={...codeArtifact,runId:artifactStream.runId};listed=[liveArtifact];
    emit('artifact-live-5','artifact_created',metadata(liveArtifact));
    await page.waitForFunction(runId=>{const m=[...document.querySelectorAll('[data-run-id]')].find(n=>n.dataset.runId===runId&&n.querySelector('.run-artifact-card'));return m&&!m.querySelector('.run-artifact-progress');},artifactStream.runId);
    emit('artifact-live-6','artifact_progress',{phase:'failed',attempt:2});
    emit('artifact-live-7','message',{type:'complete',completed:true});artifactStream.res.end();
    await page.waitForFunction(()=>!state.sending);
    await page.evaluate(async runId=>{const m=[...document.querySelectorAll('[data-run-id]')].find(n=>n.dataset.runId===runId&&n.querySelector('.run-artifact-card'));while(m._artifactFetch)await new Promise(resolve=>setTimeout(resolve,10));await RunArtifacts.refresh(m);},artifactStream.runId);
    const liveMessage=page.locator('[data-message-role=assistant]').filter({has:page.locator('.run-artifact-progress[data-phase=failed]')}).last();
    assert.equal(await liveMessage.locator('.run-artifact-card').count(),1,'successful first file remains visible');
    assert.equal(await liveMessage.locator('.run-artifact-progress[data-phase=failed]').count(),1,'second file failure survives terminal refresh of the first saved file');
    // A committed artifact must recover even when its live artifact_created event was lost.
    listed = [];
    await page.evaluate(() => {
      const message = addMessage('assistant', '', false, null, 'run-a'); message.id='recovery-artifacts';
      window.artifactRecoveryView=restoreTimelineSnapshot(message,{runId:'run-a',sessionId:state.sessionId,timelineEvents:[]},'');
    });
    await page.waitForFunction(() => !document.getElementById('recovery-artifacts')._artifactFetch);
    assert.equal(await page.locator('#recovery-artifacts .run-artifact-card').count(),0);
    listed = [documentArtifact,tableArtifact];
    await page.evaluate(() => applyRunTimelineEvent(window.artifactRecoveryView,'message',
      {runId:'run-a',sessionId:state.sessionId,type:'complete',completed:true},'recovery-complete',true));
    await page.waitForFunction(() => document.querySelectorAll('#recovery-artifacts .run-artifact-card').length===2);
    assert.deepEqual(errors, []);
    console.log('PASS run artifacts: Markdown/table/code cards, inert exact source previews, artifact progress, persisted revisions, scoped event replay, safe links, editor deep links, restore/dedup, mismatch rejection and 320px layout');
    console.log('Screenshots:', screenshots);
  } finally {await browser.close(); await new Promise(resolve => server.close(resolve));}
})().catch(error => {console.error(error); process.exitCode = 1; server.close();});
