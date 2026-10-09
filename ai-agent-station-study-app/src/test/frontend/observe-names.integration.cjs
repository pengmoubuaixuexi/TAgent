const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const os = require('node:os');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
const screenshots = fs.mkdtempSync(path.join(os.tmpdir(), 'tagent-observe-names-'));
const mcpId = 'wt_8b0f2ad215604669a5754f0327e378b9';
const agents = [
    {agentId:'wa_search_long_id',agentName:'论文助手',strategy:'flow',status:1},
    {agentId:'wa_dup_1',agentName:'研究助手',strategy:'auto',status:1},
    {agentId:'wa_dup_2',agentName:'研究助手',strategy:'flow',status:1},
    {agentId:'wa_dup_3',agentName:'研究助手',strategy:'flow',status:1}
];
let clients = [
    {mcpId,mcpName:'高德地图',status:'alive',registeredTools:['maps_around_search','maps_weather']},
    {mcpId:'wt_xss',mcpName:'<img src=x onerror=alert(1)>',status:'idle',registeredTools:[]}
];
let tools = [{mcpId,mcpName:'高德地图',tool:'maps_weather',calls:2,errors:0}];
const server = http.createServer((req,res) => {
    const route = new URL(req.url, 'http://localhost').pathname;
    const send = data => {res.writeHead(200, {'Content-Type':'application/json'});res.end(JSON.stringify({code:'0000',data}));};
    if (route === '/api/v1/auth/me') return send({userId:'alice',username:'测试用户',role:'USER'});
    if (route.endsWith('/mcp-client-health')) return send({generatedAt:Date.now(),clients,summary:{totalClients:clients.length,aliveClients:1,totalRegisteredTools:2}});
    if (route.endsWith('/mcp-tools-status')) return send({generatedAt:Date.now(),startedAt:1,tools,summary:{totalCalls:2}});
    if (route.endsWith('/query_available_agents')) return send(agents);
    if (route.startsWith('/api/')) return send([]);
    const file = path.resolve(root, '.' + (route === '/' ? '/index.html' : route));
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file)) {res.writeHead(404);return res.end('missing');}
    res.writeHead(200, {'Content-Type':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});
    fs.createReadStream(file).pipe(res);
});
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = 'http://127.0.0.1:' + server.address().port;
    const browser = await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'});
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    const errors = [], dialogs = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', async d => {dialogs.push(d.message());await d.dismiss();});
    await page.route('**/*', route => route.request().url().startsWith(base) ? route.continue() : route.abort());
    try {
        await page.goto(base + '/observe-mcp.html');
        await page.waitForFunction(() => document.getElementById('status').textContent.includes('刷新成功'));
        assert.equal(await page.locator('#clientRows .connection-name').first().textContent(), '高德地图');
        assert.equal(await page.locator('#clientRows img').count(), 0);
        assert.equal(await page.locator('#clientRows [aria-label="连接 ID"]').first().isVisible(), false);
        assert.match(await page.locator('#clientRows td').nth(1).textContent(), /可用/);
        await page.locator('#clientRows .connection-detail summary').first().click();
        assert.equal(await page.locator('#clientRows [aria-label="连接 ID"]').first().textContent(), mcpId);
        await page.locator('#clientRows .connection-detail summary').first().click();
        await page.fill('#search', '高德地图');
        assert.equal(await page.locator('#clientRows tr').count(), 1);
        assert.equal(await page.locator('#toolRows .connection-name').textContent(), '高德地图');
        await page.fill('#search', mcpId);
        assert.equal(await page.locator('#clientRows tr').count(), 1);
        await page.fill('#search', '不存在的连接');
        assert.match(await page.locator('#clientRows').textContent(), /没有匹配/);
        await page.fill('#search', '');
        for (const width of [1440,1024,768,320]) {
            await page.setViewportSize({width,height:1000});
            assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'viewport overflow at ' + width);
            assert.equal(await page.evaluate(() => {
                const cells = document.querySelector('#clientRows tr').cells;
                return cells[0].getBoundingClientRect().right <= cells[1].getBoundingClientRect().left + 1;
            }), true, 'connection and status overlap');
        }
        await page.screenshot({path:path.join(screenshots,'mobile.png'),fullPage:true});
        await page.setViewportSize({width:1440,height:1000});
        await page.screenshot({path:path.join(screenshots,'desktop.png'),fullPage:true});
        clients=[];tools=[];await page.evaluate(() => refresh());
        assert.match(await page.locator('#clientRows').textContent(), /尚未加载 MCP 连接/);
        assert.match(await page.locator('#toolRows').textContent(), /连接已加载不代表工具已被调用/);
        await page.goto(base + '/index.html?agentId=wa_search_long_id');
        await page.waitForFunction(() => document.querySelectorAll('#agentSelect option').length === 5);
        assert.deepEqual(await page.locator('#agentSelect option').allTextContents(), [
            '自动（AI 路由选择）','论文助手','研究助手 · 自主执行','研究助手 · 流程编排 · 1','研究助手 · 流程编排 · 2'
        ]);
        assert.equal(await page.inputValue('#agentSelect'), 'wa_search_long_id');
        assert.equal(await page.evaluate(() => agentLabel('wa_dup_1')), '研究助手 · 自主执行');
        assert.equal(await page.evaluate(() => backgroundTaskAgentLabel('wa_search_long_id')), '论文助手');
        assert.equal(await page.evaluate(() => backgroundTaskAgentLabel('missing_agent')), '原 Agent（当前不可用）');
        assert.deepEqual(dialogs, []);
        assert.deepEqual(errors, []);
        console.log('PASS scoped MCP names, safe HTML, optional IDs, search, lazy states, 320–1440px, Agent picker identifiers; screenshots: ' + screenshots);
    } finally {await browser.close();server.close();}
})().catch(error => {console.error(error);process.exitCode=1;server.close();});
