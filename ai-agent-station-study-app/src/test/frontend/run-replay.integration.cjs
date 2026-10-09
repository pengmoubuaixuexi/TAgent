const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../main/resources/static');
let runId,sessionId,history=[],confirmCount=0;
const frame=(id,type,data)=>`id: ${id}\nevent: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
const payload=data=>({runId,sessionId,...data});
const initial=()=>[
 ['100-0','ack',payload({})],
 ['100-1','step_start',payload({stepId:'analysis',displayName:'工具分析'})],
 ['100-2','token',payload({stepId:'analysis',token:'分析内容'})],
 ['100-3','tool_call_start',payload({toolName:'request_tool',meta:true,inputPreview:'联网搜索',step:'analysis'})],
 ['100-4','tool_call_end',payload({toolName:'request_tool',meta:true,status:'success',detail:'web_search',step:'analysis'})],
 ['100-5','tool_call_start',payload({toolName:'ask_user',meta:true,inputPreview:'什么时候？',step:'analysis'})],
 ['100-6','tool_call_end',payload({toolName:'ask_user',meta:true,status:'success',detail:'明天',step:'analysis'})],
 ['100-7','step_end',payload({stepId:'analysis'})],
 ['100-8','plan_review_required',payload({status:'PENDING',steps:[{stepNo:1,title:'检索',content:'查询资料',dependsOn:[]}]})]
];
const server=http.createServer((req,res)=>{let body='';req.on('data',d=>body+=d);req.on('end',()=>{
 const route=new URL(req.url,'http://localhost').pathname;
 const send=data=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:'0000',data}));};
 const sse=records=>{res.writeHead(200,{'Content-Type':'text/event-stream'});res.end(records.map(r=>frame(...r)).join(''));};
 if(route==='/api/v1/auth/me')return send({userId:'alice',username:'Alice',role:'USER'});
 if(route==='/api/v1/auth/csrf'){res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'fixture'}));}
 if(route.endsWith('/query_available_agents'))return send([{agentId:'a1',agentName:'Flow',strategy:'flow',status:1}]);
 if(route.endsWith('/auto_agent')){({runId,sessionId}=JSON.parse(body));history=initial();return sse([...history,history[4]]);}
 if(route.endsWith('/flow/plan-review/confirm')){confirmCount++;return sse([...history,
  ['101-0','ack',payload({planReviewResume:true})],['101-1','plan_review_resumed',payload({status:'RUNNING'})],
  ['101-2','tool_call_start',payload({toolName:'request_tool',meta:true,inputPreview:'联网搜索',step:'execute'})],
  ['101-3','tool_call_end',payload({toolName:'request_tool',meta:true,status:'success',detail:'web_search',step:'execute'})],
  ['101-4','plan_review_status',payload({status:'COMPLETED'})],['101-5','message',payload({type:'summary',content:'已完成'})]
 ]);}
 if(route.startsWith('/api/'))return send([]);
 const file=path.resolve(root,'.'+(route==='/'?'/index.html':route));
 if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);return res.end('missing');}
 res.writeHead(200,{'Content-Type':file.endsWith('.js')?'application/javascript':'text/html'});fs.createReadStream(file).pipe(res);
});});
(async()=>{await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const base='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const page=await browser.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',r=>r.request().url().startsWith(base)?r.continue():r.abort());
 try{
  await page.goto(base+'/index.html');await page.waitForFunction(()=>document.querySelectorAll('#agentSelect option').length===2);
  await page.evaluate(()=>{state.planReviewEnabled=true;state.currentAgentId='a1';document.getElementById('msgInput').value='查一下天气';return sendMessage();});
  assert.equal(await page.locator('[data-plan-review-card]').count(),1);
  assert.equal(await page.locator('[data-tool-payload]').count(),2);
  await page.evaluate(()=>{window.savedFixturePlan=loadPendingFlowPlan();});
  await page.evaluate(()=>document.querySelector('[data-action="quick-confirm"]').click());
  await page.waitForFunction(()=>document.querySelector('[data-plan-review-card]')?.dataset.planReviewStatus==='COMPLETED'&&!state.sending);
  assert.equal(confirmCount,1);assert.equal(await page.locator('[data-plan-review-card]').count(),1);
  assert.equal(await page.locator('[data-tool-payload]').count(),3,'replayed tools must not be appended, genuine second request_tool must remain');
  assert.equal(await page.locator('.step-body').first().textContent(),'分析内容','replayed tokens must not append twice');
  await page.evaluate(async()=>{document.getElementById('messageArea').innerHTML='';await restorePendingFlowPlanAfterRefresh(window.savedFixturePlan);});
  assert.equal(await page.locator('[data-plan-review-card]').count(),1);
  await page.evaluate(()=>document.querySelector('[data-action="quick-confirm"]').click());
  await page.waitForFunction(()=>document.querySelector('[data-plan-review-card]')?.dataset.planReviewStatus==='COMPLETED'&&!state.sending);
  assert.equal(confirmCount,2);
  assert.equal(await page.locator('[data-tool-payload]').count(),3,'persisted HTML must share event identity with resumed stream');
  assert.equal(await page.locator('.step-body').first().textContent(),'分析内容');
  const result=await page.evaluate(()=>{
   const snapshot={runId:'snapshot-run',sessionId:state.sessionId,timelineEvents:[]};
   let seq=0;const add=(eventType,data)=>snapshot.timelineEvents.push({eventId:`200-${++seq}`,eventType,data:{...data,runId:snapshot.runId,sessionId:state.sessionId}});
   add('tool_call_start',{toolName:'search',callId:'A',inputPreview:'first'});
   add('tool_call_start',{toolName:'search',callId:'B',inputPreview:'second'});
   add('tool_call_end',{toolName:'search',callId:'B',status:'success',resultChars:22});
   add('tool_call_end',{toolName:'search',callId:'A',status:'success',resultChars:11});
   snapshot.timelineEvents.push(snapshot.timelineEvents[0],snapshot.timelineEvents[2]);
   const msg=addMessage('assistant','',false,null,snapshot.runId),view=restoreTimelineSnapshot(msg,snapshot,'');
   for(const e of snapshot.timelineEvents)applyRunTimelineEvent(view,e.eventType,e.data,e.eventId,true);
   const count=msg.querySelectorAll('[data-tool-payload]').length;
   const calls=[...msg.querySelectorAll('[data-tool-payload]')].map(c=>JSON.parse(c.dataset.toolPayload));
   const foreignSession=acceptRunEvent(msg,{runId:snapshot.runId,sessionId:'another-session'},'900-0');
   const foreignRun=acceptRunEvent(msg,{runId:'another-run',sessionId:state.sessionId},'900-0');
   const other=addMessage('assistant','',false,null,'other-run');initializeRunEventLedger(other,'other-run',state.sessionId);
   const sameIdOtherRun=acceptRunEvent(other,{runId:'other-run',sessionId:state.sessionId},'200-1');
   const seed=serializeRunEventLedger(msg),copy=addMessage('assistant','',false,null,snapshot.runId);
   initializeRunEventLedger(copy,snapshot.runId,state.sessionId,seed);
   const persistedReplay=acceptRunEvent(copy,{runId:snapshot.runId,sessionId:state.sessionId},'200-1');
   state.userId='bob';const another=addMessage('assistant','',false,null,snapshot.runId);
   initializeRunEventLedger(another,snapshot.runId,state.sessionId,seed);
   const sameIdOtherUser=acceptRunEvent(another,{runId:snapshot.runId,sessionId:state.sessionId},'200-1');
   state.userId='alice';
   return {count,calls,foreignSession,foreignRun,sameIdOtherRun,persistedReplay,sameIdOtherUser};
  });
  assert.equal(result.count,2);assert.equal(result.calls.find(c=>c.callId==='A').resultChars,11);assert.equal(result.calls.find(c=>c.callId==='B').resultChars,22);
  assert.equal(result.foreignSession,false);assert.equal(result.foreignRun,false);assert.equal(result.sameIdOtherRun,true);assert.equal(result.persistedReplay,false);assert.equal(result.sameIdOtherUser,true);
  assert.deepEqual(errors,[]);
  console.log('PASS full replay on plan confirm, repeated tokens/cards, parallel same-name call IDs, snapshot replay, persisted ledger, run/session/account isolation');
 }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);process.exitCode=1;server.close();});
