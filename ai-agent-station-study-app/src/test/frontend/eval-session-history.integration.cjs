const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../main/resources/static');
const sid='eval-b8b7c137-e2e100-main',cid='default:10001:'+sid,rid='0a70429c-c263-428c-9739-2644f9a6fdb2';
let messagesStatus=200,durableOwner='10001',durableSession=sid,delayReplay=false,heldReplay=null,includePlan=false;
let emptyMessages=false,sessionRuns=[],sessionStatus=200,detailStatus=200,delaySession=false,heldSession=null;
let earlierMessages=[];
const extraDetails=new Map();
const requests=[],errors=[];
const messages=[{id:1,messageType:'USER',content:'评测原始问题',runId:rid},{id:2,messageType:'ASSISTANT',content:'数据库中的最终答案',runId:rid}];
const run=()=>({runId:rid,userId:durableOwner,sessionId:durableSession,status:'COMPLETED',answer:'数据库中的最终答案',events:[
  {eventId:'100-0',eventType:'step_start',payload:{runId:rid,sessionId:sid,stepId:'analysis',displayName:'持久化评测分析'}},
  {eventId:'100-1',eventType:'token',payload:{runId:rid,sessionId:sid,stepId:'analysis',token:'Redis 过期后仍然可见的步骤'}},
  {eventId:'100-2',eventType:'step_end',payload:{runId:rid,sessionId:sid,stepId:'analysis'}},
  ...(includePlan?[{eventId:'100-3',eventType:'plan_review_required',payload:{runId:rid,sessionId:sid,status:'PENDING',steps:[{stepNo:1,title:'旧计划',content:'旧步骤',dependsOn:[]}]}}]:[])
]});
const server=http.createServer((req,res)=>{
  const u=new URL(req.url,'http://localhost'),route=u.pathname;requests.push({method:req.method,url:req.url});
  const send=data=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:'0000',data}));};
  const fail=status=>{res.writeHead(status,{'Content-Type':'text/html'});res.end('<html>fixture unavailable</html>');};
  if(route==='/api/v1/auth/me')return send({userId:'10001',username:'Admin',role:'ADMIN'});
  if(route==='/api/v1/auth/csrf'){res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'fixture'}));}
  if(route.endsWith('/query_available_agents'))return send([{agentId:'a1',agentName:'Flow',strategy:'flow',status:1}]);
  if(route.endsWith('/conversations'))return send([{conversationId:cid,firstMessage:'评测原始问题',messageCount:2,lastTime:'2026-10-10T08:36:00Z'}]);
  if(route.endsWith('/active-runs'))return fail(503);
  if(route.endsWith('/conversation_messages')){
    assert.equal(u.searchParams.get('conversationId'),cid);
    if(u.searchParams.has('beforeId'))return send({messages:earlierMessages,oldestId:0,hasMore:false});
    return messagesStatus===200?send({messages:emptyMessages?[]:messages,oldestId:emptyMessages?null:1,hasMore:false}):fail(messagesStatus);
  }
  if(route.includes('/run-snapshots'))return fail(route.endsWith('/run-snapshots')?503:403);
  if(route==='/api/v1/history/session/'+sid){
    assert.equal(u.searchParams.get('limit'),'50');
    if(sessionStatus!==200)return fail(sessionStatus);
    const offset=Number(u.searchParams.get('offset'));
    if(delaySession){heldSession=()=>send(sessionRuns.slice(offset,offset+50));return;}
    return send(sessionRuns.slice(offset,offset+50));
  }
  if(extraDetails.has(route)){return detailStatus===200?send(extraDetails.get(route)):fail(detailStatus);}
  if(route==='/api/v1/history/'+rid){
    if(delayReplay){heldReplay=()=>send(run());return;}
    return send(run());
  }
  if(route.startsWith('/api/'))return send([]);
  const file=path.resolve(root,'.'+(route==='/'?'/index.html':route));
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);return res.end('missing');}
  res.writeHead(200,{'Content-Type':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});fs.createReadStream(file).pipe(res);
});
(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base='http://127.0.0.1:'+server.address().port;
  const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const page=await browser.newPage();page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/*',route=>route.request().url().startsWith(base)?route.continue():route.abort());
  try {
    await page.goto(base+'/index.html');
    await page.waitForFunction(()=>state.userId==='10001'&&document.querySelectorAll('#agentSelect option').length===2);
    await page.evaluate(()=>loadHistory());
    assert.match(await page.textContent('#historyList'),/评测原始问题/,'active-runs failure must preserve persisted conversations');
    await page.evaluate(()=>document.querySelector('#historyList [onclick^="loadConversation"]').click());
    await page.waitForFunction(()=>document.querySelector('.step-body')?.textContent.includes('Redis 过期后'));
    assert.equal(await page.evaluate(()=>state.sessionId),sid);
    assert.match(await page.textContent('#messageArea'),/数据库中的最终答案/);
    assert.equal(await page.locator('.step-card').count(),1);
    assert.equal(await page.evaluate(()=>state.sending),false,'durable replay must not resume a run');
    await page.evaluate(id=>loadConversation(id),cid);
    assert.equal(await page.locator('.step-card').count(),1,'reloading must not duplicate diagnostics');
    includePlan=true;
    await page.evaluate(id=>loadConversation(id),cid);
    assert.equal(await page.locator('[data-action="quick-confirm"]').isDisabled(),true,'durable plan checkpoints are read-only');
    assert.match(await page.textContent('[data-plan-review-card]'),/历史记录仅供查看/);
    includePlan=false;
    sessionRuns=[{...run(),question:'已展示的回答'}];
    await page.evaluate(id=>loadConversation(id),cid);
    assert.equal(await page.locator('[data-session-run-history]').count(),0,'do not duplicate a ChatMemory answer');
    sessionRuns=[];
    durableOwner='someone-else';
    await page.evaluate(id=>loadConversation(id),cid);
    assert.equal(await page.locator('.step-card').count(),0,'foreign owner diagnostics are rejected');
    assert.match(await page.textContent('#messageArea'),/数据库中的最终答案/);
    durableOwner='10001';durableSession='other-session';
    await page.evaluate(id=>loadConversation(id),cid);
    assert.equal(await page.locator('.step-card').count(),0,'foreign session diagnostics are rejected');
    durableSession=sid;
    assert.match(await page.evaluate(async()=>{
      try { await readConversationResponse(new Response('',{status:401})); }
      catch(error) { return error.message; }
    }),/登录已失效/);
    for(const [status,text] of [[403,'无权访问该会话'],[503,'服务暂时不可用']]){
      messagesStatus=status;
      assert.equal(await page.evaluate(id=>loadConversation(id),cid),false);
      assert.match(await page.textContent('#messageArea'),new RegExp(text));
      assert.equal(await page.getByRole('button',{name:'重试加载',exact:true}).count(),1);
    }
    messagesStatus=200;
    await page.getByRole('button',{name:'重试加载',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('.step-body')?.textContent.includes('Redis 过期后'));
    emptyMessages=true;
    const failed={runId:'failed-eval',sessionId:sid,userId:'10001',status:'FAILED',question:'失败任务的原始问题 <img src=x onerror=window.bad=1>',error:'模型没有返回正文',createdAt:'2026-10-10T08:34:39Z',events:run().events};
    failed.events=failed.events.map(event=>({...event,payload:{...event.payload,runId:failed.runId}}));
    const cancelled={...failed,runId:'cancelled-eval',status:'CANCELLED',question:'取消的任务',error:'用户取消',createdAt:'2026-10-10T08:36:00Z',events:[]};
    sessionRuns=[cancelled,failed,{...failed,runId:'foreign-user',userId:'other'},{...failed,runId:'foreign-session',sessionId:'other'}];
    extraDetails.set('/api/v1/history/failed-eval',failed);
    extraDetails.set('/api/v1/history/cancelled-eval',cancelled);
    const detailCount=()=>requests.filter(request=>request.url==='/api/v1/history/failed-eval').length;
    await page.evaluate(id=>loadConversation(id),cid);
    assert.equal(await page.locator('[data-persisted-run-id]').count(),2,'discover zero-message terminal runs without Redis');
    assert.match(await page.textContent('#messageArea'),/模型没有返回正文/);
    assert.match(await page.textContent('#messageArea'),/已取消/);
    assert.equal(detailCount(),0,'large event details must load on demand');
    assert.equal(await page.locator('#messageArea img').count(),0,'question previews are rendered as text');
    assert.equal(await page.evaluate(()=>window.bad),undefined);
    detailStatus=503;
    await page.locator('[data-persisted-run-id="failed-eval"] > summary').click();
    await page.getByRole('button',{name:'重试读取',exact:true}).waitFor();
    detailStatus=200;
    await page.getByRole('button',{name:'重试读取',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('[data-persisted-run-id="failed-eval"] .step-body')?.textContent.includes('Redis 过期后'));
    await page.locator('[data-persisted-run-id="cancelled-eval"] > summary').click();
    await page.getByText('未保存详细执行事件；上方显示运行状态和错误。',{exact:true}).waitFor();
    assert.equal(await page.evaluate(()=>state.sending),false);
    if(process.env.EVAL_SESSION_SCREENSHOT){
      await page.setViewportSize({width:1366,height:960});
      await page.screenshot({path:process.env.EVAL_SESSION_SCREENSHOT,fullPage:true});
    }
    await page.evaluate(id=>loadConversation(id),cid);
    assert.equal(await page.locator('[data-persisted-run-id]').count(),2,'refresh does not duplicate terminal records');
    earlierMessages=[{id:0,messageType:'ASSISTANT',content:'早期聊天回答',runId:'failed-eval'}];
    await page.evaluate(async()=>{historyPage.hasMore=true;historyPage.oldestId=1;await loadHistoryEarlier();});
    assert.equal(await page.locator('[data-persisted-run-id="failed-eval"]').count(),0,'earlier ChatMemory must replace the same durable run card');
    sessionRuns=Array.from({length:51},(_,i)=>({...cancelled,runId:'page-'+i,question:'分页任务 '+i}));
    await page.evaluate(id=>loadConversation(id),cid);
    assert.equal(await page.locator('[data-persisted-run-id]').count(),50);
    await page.getByRole('button',{name:'加载更早的运行记录',exact:true}).click();
    await page.waitForFunction(()=>document.querySelectorAll('[data-persisted-run-id]').length===51);
    assert.equal(await page.getByRole('button',{name:'加载更早的运行记录',exact:true}).isVisible(),false);
    sessionRuns=[failed];sessionStatus=503;
    await page.evaluate(id=>loadConversation(id),cid);
    assert.match(await page.textContent('#messageArea'),/运行记录加载失败/);
    sessionStatus=200;
    await page.getByRole('button',{name:'重试读取运行记录',exact:true}).click();
    await page.waitForFunction(()=>document.querySelectorAll('[data-persisted-run-id]').length===1);
    delaySession=true;
    await page.evaluate(id=>{window.pendingSession=loadConversation(id);},cid);
    while(!heldSession)await new Promise(resolve=>setTimeout(resolve,10));
    await page.evaluate(()=>newSession());heldSession();heldSession=null;
    assert.equal(await page.evaluate(()=>window.pendingSession),false,'stale conversation load must not report success');
    assert.equal(await page.locator('[data-session-run-history]').count(),0,'late discovery cannot populate another conversation');
    delaySession=false;sessionRuns=[];emptyMessages=false;
    delayReplay=true;
    await page.evaluate(id=>{window.pendingHistory=loadConversation(id);},cid);
    await page.waitForFunction(()=>document.querySelector('#messageArea')?.textContent.includes('数据库中的最终答案'));
    while(!heldReplay)await new Promise(resolve=>setTimeout(resolve,10));
    await page.evaluate(()=>newSession());
    heldReplay();heldReplay=null;
    await page.evaluate(()=>window.pendingHistory);
    assert.equal(await page.locator('.step-card').count(),0,'late history cannot contaminate a new session');
    assert.doesNotMatch(await page.textContent('#messageArea'),/持久化评测分析|Redis 过期后/);
    assert.equal(requests.some(request=>request.url.includes('/stream')),false);
    assert.equal(requests.some(request=>request.method!=='GET'),false);
    assert.deepEqual(errors,[]);
    console.log('PASS Eval session replay plus zero-message FAILED/CANCELLED discovery, lazy details, pagination, owner/session rejection, XSS, retry, deduplication and stale response isolation; no external requests or model execution.');
  } finally {if(heldReplay)heldReplay();if(heldSession)heldSession();await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
