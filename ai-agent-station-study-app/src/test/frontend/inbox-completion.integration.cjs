const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), http = require('node:http');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
let rows = [], acknowledgements = [], snapshotReads = [], authDenied = false;
let allReads = [], bulkFailure = false, holdBulk = false, releaseBulk;
const terminal = (id, runId, sessionId = 's1', type = 'TASK_COMPLETED') => ({notificationId:id,runId,sessionId,type,
  status:type,readStatus:'UNREAD',title:'北京攻略已完成 <img src=x onerror=alert(1)>',summary:'点击查看结果',createdAt:Date.now()});
const server = http.createServer((req,res) => {
  const url = new URL(req.url, 'http://localhost'), route = url.pathname;
  const send = data => {res.writeHead(200, {'Content-Type':'application/json'});res.end(JSON.stringify({code:'0000',data}));};
  if (route === '/api/v1/auth/me') {
    if (authDenied) {res.writeHead(401, {'Content-Type':'application/json'});return res.end(JSON.stringify({code:'401',info:'请重新登录'}));}
    return send({userId:'alice',username:'Alice',role:'USER'});
  }
  if (route === '/api/v1/auth/csrf') {res.writeHead(200, {'Content-Type':'application/json'});return res.end(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'fixture'}));}
  if (req.method === 'POST' && route.startsWith('/api/v1/agent/notifications/') && req.headers['x-csrf-token'] !== 'fixture') {
    res.writeHead(403, {'Content-Type':'application/json'});
    return res.end(JSON.stringify({code:'403',info:'CSRF token is required'}));
  }
  if (route.endsWith('/query_available_agents')) return send([{agentId:'a1',agentName:'Flow',strategy:'flow',status:1}]);
  if (route === '/api/v1/agent/notifications') return send(rows);
  if (route === '/api/v1/agent/notifications/ack-all') {
    const before = Number(url.searchParams.get('before'));
    allReads.push({before,method:req.method,csrf:req.headers['x-csrf-token']});
    const finish = () => {
      if (bulkFailure) {res.writeHead(500,{'Content-Type':'application/json'});return res.end(JSON.stringify({code:'9999',info:'fixture unavailable'}));}
      let count = 0;
      for (const item of rows) {
        if (item.readStatus !== 'UNREAD' || Number(item.updatedAt || item.createdAt) > before) continue;
        item.readStatus='ACKNOWLEDGED'; item.acknowledgedAt=Date.now(); count++;
      }
      send(count);
    };
    if (holdBulk) releaseBulk=finish; else finish();
    return;
  }
  if (route.startsWith('/api/v1/agent/notifications/')) {
    const id = decodeURIComponent(route.split('/')[5]);
    const item = rows.find(value => value.notificationId === id);
    if (route.endsWith('/ack')) {acknowledgements.push(id);if(item)item.readStatus='ACKNOWLEDGED';return send(item);}
    return send(item || terminal(id,'deep-run','deep-session'));
  }
  if (route === '/api/v1/agent/conversation_messages') return send({messages:[],hasMore:false});
  if (route.startsWith('/api/v1/agent/run-snapshots/')) {snapshotReads.push(route);return send(null);}
  if (route.startsWith('/api/v1/history/')) return send({runId:route.split('/').at(-1),sessionId:'deep-session',answer:'这是目标任务的最终结果',agentId:'a1'});
  if (route.startsWith('/api/')) return send([]);
  if (route === '/inbox-fixture.html') {
    res.writeHead(200,{'Content-Type':'text/html'});
    return res.end('<!doctype html><html><head><meta charset="UTF-8"><script src="/auth.js"></script><link rel="stylesheet" href="/workspace-inbox.css"></head><body><button id="open" onclick="TAgentInbox.open()">收件箱</button><script src="/workspace-inbox.js"></script></body></html>');
  }
  const file = path.resolve(root, '.' + (route === '/' ? '/index.html' : route));
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file)) {res.writeHead(404);return res.end('missing');}
  res.writeHead(200, {'Content-Type':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});fs.createReadStream(file).pipe(res);
});
(async () => {
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  const browser = await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const page = await browser.newPage(), errors = [];
  page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/*',route=>route.request().url().startsWith(base)?route.continue():route.abort());
  try {
    await page.goto(base + '/index.html');
    await page.waitForFunction(()=>document.querySelectorAll('#agentSelect option').length===2);
    await page.evaluate(()=>{
      stopNotificationPolling();state.sessionId='s1';document.hasFocus=()=>true;
      Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>window.testVisibility||'visible'});
      const area=document.getElementById('messageArea');area.innerHTML='';area.getBoundingClientRect=()=>({top:0,bottom:600});
      const node=addMessage('assistant','当前任务结果',false,'a1','viewed-run');
      node.getBoundingClientRect=()=>({top:100,bottom:250});
      const offscreen=addMessage('assistant','旧任务',false,'a1','old-run');
      offscreen.getBoundingClientRect=()=>({top:-300,bottom:-20});
    });
    rows=[terminal('current','viewed-run'),terminal('old','old-run'),terminal('other-session','elsewhere','s2'),
      {...terminal('plan','plan-run'),type:'PLAN_REVIEW',status:'PLAN_REVIEW'}];
    await page.evaluate(()=>refreshNotifications(true));
    assert.deepEqual(acknowledgements,['current'],'only a visible terminal run is auto-read');
    await page.evaluate(()=>refreshNotifications(true));
    assert.deepEqual(acknowledgements,['current'],'polling does not acknowledge the same completed run again');
    assert.equal(rows.find(row=>row.notificationId==='old').readStatus,'UNREAD');
    assert.equal(rows.find(row=>row.notificationId==='other-session').readStatus,'UNREAD');
    assert.equal(rows.find(row=>row.notificationId==='plan').readStatus,'UNREAD','completion handling must not consume a pending plan');
    rows=[terminal('hidden-tab','viewed-run')];
    await page.evaluate(()=>{window.testVisibility='hidden';return refreshNotifications(true);});
    assert.equal(rows[0].readStatus,'UNREAD','background browser tab must retain the completion notice');
    await page.evaluate(()=>{window.testVisibility='visible';return refreshNotifications(true);});
    assert.equal(rows[0].readStatus,'ACKNOWLEDGED');
    rows=[terminal('failed-elsewhere','failed-run','s2','TASK_FAILED')];
    await page.evaluate(()=>refreshNotifications(true));
    assert.equal(await page.evaluate(()=>state.notifications[0].readStatus),'UNREAD');
    assert.match(await page.evaluate(()=>notificationVisual(state.notifications[0]).label),/失败/);

    rows=[terminal('deep','deep-run','deep-session')];
    await page.goto(base + '/index.html?notificationId=deep');
    await page.waitForFunction(()=>document.querySelector('[data-run-id="deep-run"]'));
    await page.waitForFunction(()=>state.notifications.some(item=>item.notificationId==='deep'&&item.readStatus==='ACKNOWLEDGED'));
    assert.equal(await page.evaluate(()=>state.sessionId),'deep-session');
    assert.match(await page.locator('[data-run-id="deep-run"]').textContent(),/目标任务的最终结果/);
    assert(snapshotReads.some(value=>value.endsWith('/deep-run')),'deep link resolves the exact run outside the newest message page');
    assert(!page.url().includes('notificationId='),'link is consumed after opening');

    const chatSnapshot = Date.now() - 1000;
    rows=[{...terminal('chat-bulk','chat-bulk-run','s-other'),createdAt:chatSnapshot},
      {...terminal('chat-plan','chat-plan-run','s-other'),type:'PLAN_REVIEW',status:'PLAN_REVIEW',createdAt:chatSnapshot}];
    await page.evaluate(()=>{stopNotificationPolling();return refreshNotifications(true);});
    await page.evaluate(()=>TAgentInbox.open());
    await page.click('#notificationMarkAllRead');
    await page.waitForFunction(()=>TAgentInbox.counts().unread===0);
    assert.equal(rows[1].status,'PLAN_REVIEW','chat bulk read does not confirm pending plans');
    assert.equal(await page.locator('#notificationMarkAllRead').isDisabled(),true);
    assert.deepEqual(await page.evaluate(()=>TAgentInbox.counts()),{unread:0,pending:1});
    assert.equal(allReads.at(-1).before,chatSnapshot,'chat passes its loaded snapshot cutoff');

    // Exercise the real read-only history page: its shared inbox still performs CSRF-protected writes.
    rows=[terminal('history-single','history-run','s3')];
    await page.goto(base + '/history.html');
    await page.waitForFunction(()=>window.TAgentInbox?.counts().unread===1);
    await page.locator('[data-nav-key="inbox"]').click();
    await page.getByRole('button',{name:'标为已读',exact:true}).click();
    await page.waitForFunction(()=>window.TAgentInbox.counts().unread===0);
    assert.equal(rows[0].readStatus,'ACKNOWLEDGED','real history page uses authenticated CSRF transport for single read');
    rows=[terminal('history-bulk','history-bulk-run','s3')];
    await page.evaluate(()=>TAgentInbox.refresh());
    await page.getByRole('button',{name:'一键已读',exact:true}).click();
    await page.waitForFunction(()=>window.TAgentInbox.counts().unread===0);
    assert.equal(allReads.at(-1).csrf,'fixture','real history page uses shared CSRF transport for bulk read');

    rows=[terminal('workspace','workspace-run','s3')];
    await page.goto(base + '/inbox-fixture.html');
    await page.waitForFunction(()=>window.TAgentInbox?.counts().unread===1);
    await page.click('#open');
    await page.waitForSelector('[data-notification-id="workspace"]');
    assert.equal(await page.locator('#workspaceInbox img').count(),0,'untrusted titles must render as text');
    assert.equal(await page.locator('#workspaceInbox a').getAttribute('href'),'/index.html?notificationId=workspace');
    assert.equal(rows[0].readStatus,'UNREAD','opening a workspace inbox is not opening its task');
    await page.getByRole('button',{name:'标为已读'}).click();
    await page.waitForFunction(()=>window.TAgentInbox.counts().unread===0);
    assert.equal(rows[0].readStatus,'ACKNOWLEDGED');

    const workspaceSnapshot = Date.now() - 1000;
    rows=[{...terminal('workspace-bulk','bulk-run','s3'),createdAt:workspaceSnapshot},
      {...terminal('workspace-approval','approval-run','s3'),type:'TOOL_APPROVAL',status:'WAITING',createdAt:workspaceSnapshot}];
    await page.evaluate(()=>TAgentInbox.refresh());
    holdBulk=true;
    const beforeRequests=allReads.length;
    await page.getByRole('button',{name:'一键已读',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('[data-inbox-mark-all]').disabled);
    await page.evaluate(()=>TAgentInbox.markAllRead());
    // Wait for the held request to reach the fixture without completing the mutation.
    await new Promise((resolve,reject)=>{
      const deadline=Date.now()+5000;
      const check=()=>allReads.length>beforeRequests?resolve():Date.now()>deadline?reject(new Error('bulk request missing')):setTimeout(check,10);
      check();
    });
    assert.equal(allReads.length,beforeRequests+1,'in-flight clicks submit only one request');
    assert.equal(allReads.at(-1).before,workspaceSnapshot);
    rows.push({...terminal('arrived-during-read','new-run','s3'),createdAt:workspaceSnapshot+1});
    holdBulk=false; releaseBulk();
    await page.waitForFunction(()=>TAgentInbox.counts().unread===1);
    assert.equal(rows[0].readStatus,'ACKNOWLEDGED');
    assert.equal(rows[1].readStatus,'ACKNOWLEDGED');
    assert.equal(rows[1].status,'WAITING','bulk read does not approve or execute pending work');
    assert.equal(rows[2].readStatus,'UNREAD','a new notification outside the snapshot stays unread');
    assert.deepEqual(await page.evaluate(()=>TAgentInbox.counts()),{unread:1,pending:1});

    bulkFailure=true;
    await page.getByRole('button',{name:'一键已读',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('[data-inbox-message]').textContent.includes('一键已读失败'));
    assert.equal(await page.getByRole('button',{name:'一键已读',exact:true}).isEnabled(),true);
    assert.equal(await page.evaluate(()=>TAgentInbox.counts().unread),1,'failed bulk mutation does not clear unread badges');
    bulkFailure=false;
    await page.reload();
    await page.waitForFunction(()=>TAgentInbox.counts().unread===1);
    await page.click('#open');
    await page.getByRole('button',{name:'一键已读',exact:true}).click();
    await page.waitForFunction(()=>TAgentInbox.counts().unread===0);
    assert.equal(await page.getByRole('button',{name:'一键已读',exact:true}).isDisabled(),true);
    assert(allReads.every(request=>request.method==='POST'&&request.csrf==='fixture'),'every bulk mutation uses the shared CSRF transport');
    rows=[]; await page.evaluate(()=>TAgentInbox.refresh());
    assert.equal(await page.getByRole('button',{name:'一键已读',exact:true}).isDisabled(),true,'empty inbox disables bulk read');
    authDenied = true;
    await page.evaluate(()=>TAgentInbox.refresh());
    assert.deepEqual(await page.evaluate(()=>TAgentInbox.counts()),{unread:0,pending:0});
    assert.equal(await page.locator('#workspaceInbox [data-notification-id]').count(),0,'expired login clears previous account notifications');
    assert.deepEqual(errors,[]);
    console.log('Inbox completion integration passed: visibility and deep links; chat/workspace bulk read, pending preservation, snapshot races, duplicate clicks, CSRF, failure recovery and safe rendering.');
  } finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
