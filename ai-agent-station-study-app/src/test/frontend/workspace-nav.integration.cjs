const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');

const root = path.resolve(__dirname, '../../main/resources/static');
const pages = ['index', 'user-agents', 'models', 'usage', 'knowledge', 'history', 'outcomes', 'observe', 'observe-mcp', 'memory', 'eval', 'account', 'admin-stats', 'agent-config'];
const expected = ['chat', 'agents', 'models', 'knowledge', 'memory', 'history', 'outcomes', 'eval', 'usage', 'observe', 'mcp', 'inbox', 'account'];
let account = {username: '<img src=x onerror=window.bad=1>', role: 'USER'};
const server = http.createServer((req, res) => {
  const route = new URL(req.url, 'http://localhost').pathname;
  if (route === '/api/v1/auth/me') {
    res.writeHead(account ? 200 : 401, {'Content-Type': 'application/json'});
    return res.end(JSON.stringify({code: account ? '0000' : '401', data: account}));
  }
  if (route === '/workspace-inbox.js') {
    res.writeHead(200, {'Content-Type': 'application/javascript'});
    return res.end('window.TAgentInbox={open:()=>window.inboxOpened=(window.inboxOpened||0)+1};');
  }
  if (route === '/workspace-inbox.css') { res.writeHead(200, {'Content-Type': 'text/css'}); return res.end(''); }
  const file = path.resolve(root, '.' + route);
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  let content = fs.readFileSync(file, 'utf8');
  if (file.endsWith('.html')) {
    // Exercise each real page's DOM and CSS, while isolating unrelated API/model/page logic.
    content = content.replace(/<script\b([^>]*)>[\s\S]*?<\/script>/gi, (tag, attrs) => attrs.includes('/workspace-nav.js') ? tag : '');
  }
  res.writeHead(200, {'Content-Type': file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html'});
  res.end(content);
});

(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const page = await browser.newPage({viewport: {width: 1440, height: 900}}), errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/*', route => new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort());
  const origin = 'http://127.0.0.1:' + server.address().port;
  try {
    for (const name of pages) {
      await page.goto(origin + '/' + name + '.html');
      await page.waitForSelector('#workspace-nav');
      await page.waitForFunction(() => parseFloat(document.documentElement.style.getPropertyValue('--workspace-nav-height')) > 0);
      assert.deepEqual(await page.locator('#workspace-nav [data-nav-key]').evaluateAll(nodes => nodes.map(node => node.dataset.navKey)), expected, name + ': identical non-admin navigation');
      assert.equal(await page.locator('[data-workspace-nav]').count(), 1, name + ': one navigation mount');
      assert.equal(await page.locator('#workspace-nav img').count(), 0);
      if (!['admin-stats', 'agent-config'].includes(name)) {
        assert.equal(await page.locator('#workspace-nav [aria-current="page"]').count(), 1, name + ': exactly one active link');
        assert.equal(await page.locator('#workspace-nav [aria-current="page"]').getAttribute('href'), '/' + name + '.html');
      }
      await page.setViewportSize({width: 375, height: 812});
      assert.equal(await page.locator('#workspace-nav').evaluate(node => node.scrollWidth <= node.clientWidth + 1), true, name + ': mobile navigation has no horizontal overflow');
      await page.setViewportSize({width: 1440, height: 900});
    }
    await page.goto(origin + '/user-agents.html'); await page.waitForSelector('#workspace-nav');
    const groups = page.locator('#workspace-nav details');
    await groups.nth(0).locator('summary').click(); assert.equal(await groups.nth(0).getAttribute('open'), '');
    assert.equal(await page.locator('#workspace-nav [data-nav-key="models"]').isVisible(), true);
    await groups.nth(1).locator('summary').click(); await page.waitForFunction(() => !document.querySelector('#workspace-nav details').open);
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#workspace-nav details[open]').count(), 0);
    assert.equal(await groups.nth(1).locator('summary').evaluate(node => node === document.activeElement), true, 'Escape restores keyboard focus');
    await groups.nth(0).locator('summary').focus(); await page.keyboard.press('Enter');
    assert.equal(await page.locator('#workspace-nav [data-nav-key="models"]').isVisible(), true, 'native keyboard menu');
    await page.locator('main').click({position: {x: 10, y: 10}}); assert.equal(await page.locator('#workspace-nav details[open]').count(), 0);
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('tagent:inbox-count', {detail: {unread: 3}})));
    assert.equal(await page.getByRole('link', {name: '收件箱，3 条未读', exact: true}).isVisible(), true);
    await page.evaluate(() => document.addEventListener('click', () => window.inboxOpened = 0));
    await page.locator('[data-nav-key="inbox"]').click(); assert.equal(await page.evaluate(() => window.inboxOpened), 1, 'inbox opening does not bubble into chat outside-close handler');
    await page.evaluate(() => localStorage.setItem('agent_login', JSON.stringify({role: 'ADMIN'})));
    await page.reload(); await page.waitForSelector('#workspace-nav'); assert.equal(await page.locator('[data-nav-key="stats"]').count(), 0, 'cached role does not grant admin navigation');
    account = {...account, role: 'ADMIN'}; await page.evaluate(() => WorkspaceNav.refresh());
    assert.equal(await page.locator('[data-nav-key="stats"]').count(), 1); assert.equal(await page.locator('[data-nav-key="config"]').count(), 1);
    account = null; await page.evaluate(() => WorkspaceNav.refresh());
    assert.equal(await page.locator('[data-workspace-nav]').isHidden(), true); assert.equal(await page.locator('#workspace-nav').count(), 0);
    assert.deepEqual(errors, []);
    console.log('PASS shared workspace navigation: 14 real page shells, consistent links, active page, mobile layout, keyboard/outside close, inbox, server-role gating and anonymous state');
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => {console.error(error); process.exitCode = 1; server.close();});
