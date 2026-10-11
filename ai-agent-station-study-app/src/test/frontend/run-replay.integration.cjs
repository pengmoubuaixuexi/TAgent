const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../main/resources/static');
let runId,sessionId,history=[],confirmCount=0,preparationResponse=null;
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
 if(route.endsWith('/auto_agent')){
  const input=JSON.parse(body);({runId,sessionId}=input);
  if(input.message==='fixture preparation progress'){
   res.writeHead(200,{'Content-Type':'text/event-stream'});
   res.write(frame('','ack',payload({})));
   res.write(frame('','run_progress',payload({phase:'mcp_connecting',resourceName:'高德地图',waitSeconds:12,timestamp:100})));
   preparationResponse=res;return;
  }
  history=initial();return sse([...history,history[4]]);
 }
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
  const progressResult=await page.evaluate(()=>{
   const rid='progress-run',sid=state.sessionId,msg=addMessage('assistant','',false,null,rid);
   const view=restoreTimelineSnapshot(msg,{runId:rid,sessionId:sid,timelineEvents:[]},'');
   const emit=(type,data,id='')=>applyRunTimelineEvent(view,type,{runId:rid,sessionId:sid,...data},id,true);
   emit('step_start',{stepId:'s1',displayName:'查询景点'},'300-1');
   emit('token',{stepId:'s1',token:'已有结果'},'300-2');
   const cursorBefore=msg._runEventLedger.lastEventId;
   emit('step_progress',{stepId:'s1',phase:'awaiting_model',message:'<img src=x onerror=alert(1)>',waitSeconds:10,timestamp:10});
   const model=msg.querySelector('.step-status').textContent;
   emit('step_progress',{stepId:'s1',phase:'tool_running',waitSeconds:20,timestamp:20});
   const tool=msg.querySelector('.step-status').textContent;
   emit('step_progress',{stepId:'s1',phase:'awaiting_model',waitSeconds:99,timestamp:1});
   const outOfOrder=msg.querySelector('.step-status').textContent;
   emit('step_progress',{stepId:'s1',phase:'awaiting_user',waitSeconds:30,timestamp:30});
   const user=msg.querySelector('.step-status').textContent;
   emit('step_progress',{stepId:'unknown',phase:'awaiting_model',timestamp:30});
   emit('step_progress',{runId:'foreign-run',stepId:'s1',phase:'awaiting_model',timestamp:40});
   const cursorAfter=msg._runEventLedger.lastEventId;
   const content=msg.querySelector('.step-body').textContent;
   emit('step_end',{stepId:'s1',status:'failed',summary:'模型超时'},'300-3');
   emit('step_progress',{stepId:'s1',phase:'tool_running',waitSeconds:40,timestamp:50});
   const terminal=msg.querySelector('.step-status').textContent;
   return {model,tool,user,outOfOrder,cursorBefore,cursorAfter,content,terminal,
    failed:msg.querySelector('.step-card').classList.contains('failed'),cards:msg.querySelectorAll('.step-card').length,images:msg.querySelectorAll('img').length};
  });
  assert.equal(progressResult.model,'等待模型响应 · 已等待 10 秒');
  assert.equal(progressResult.tool,'正在等待工具结果 · 已等待 20 秒');
  assert.equal(progressResult.user,'等待你的补充或确认 · 已等待 30 秒');
  assert.equal(progressResult.outOfOrder,progressResult.tool);
  assert.equal(progressResult.cursorBefore,progressResult.cursorAfter,'transient progress does not advance replay cursor');
  assert.equal(progressResult.content,'已有结果');assert.equal(progressResult.terminal,'模型超时');
  assert.equal(progressResult.failed,true);assert.equal(progressResult.cards,1);assert.equal(progressResult.images,0);
  const dependencyResult=await page.evaluate(()=>{
   const rid='dependency-run',sid=state.sessionId,step=n=>`flow_step4_execute_step_${n}`;
   const snapshot={runId:rid,sessionId:sid,timelineEvents:[]};
   let seq=0;
   const record=(eventType,data)=>({eventId:`400-${++seq}`,eventType,data:{runId:rid,sessionId:sid,...data}});
   snapshot.timelineEvents.push(
    record('step_pending',{stepId:step(3),displayName:'整理攻略',dependsOn:[step(1),step(2)]}),
    record('step_pending',{stepId:step(4),dependsOn:[step(3)]}),
    record('step_pending',{stepId:step(5),dependsOn:[step(3),step(4)]}),
    record('step_start',{stepId:step(1),displayName:'查询天气',dependsOn:[]}),
    record('step_end',{stepId:step(1)}),
    record('step_start',{stepId:step(2),displayName:'查询景点',dependsOn:[]}),
    record('step_end',{stepId:step(2)}));
   const msg=addMessage('assistant','',false,null,rid);
   let view=restoreTimelineSnapshot(msg,snapshot,'');
   const inspect=id=>{
    const entry=view.stepById[id],card=entry.card;
    return {name:card.querySelector('.step-name').textContent,
     badges:[...card.querySelectorAll('.step-dep')].map(b=>b.textContent),
     body:card.querySelector('.step-body').textContent,
     status:['pending','running','done','failed','blocked'].find(s=>card.classList.contains(s))};
   };
   const emit=(type,data)=>{
    const e=record(type,data);snapshot.timelineEvents.push(e);
    applyRunTimelineEvent(view,e.eventType,e.data,e.eventId,true);return e;
   };
   const pending=inspect(step(3));
   emit('step_start',{stepId:step(3)});
   const started=inspect(step(3));
   emit('token',{stepId:step(3),token:'完整攻略正文'});
   const token=inspect(step(3));
   applyRunTimelineEvent(view,'step_progress',{runId:rid,sessionId:sid,stepId:step(3),phase:'awaiting_model',waitSeconds:10,timestamp:10},'',true);
   const progress=inspect(step(3));
   const completedEvent=emit('step_end',{stepId:step(3)});
   const completed=inspect(step(3));
   emit('step_start',{stepId:step(4)});
   emit('step_end',{stepId:step(4),status:'failed',summary:'查询失败'});
   emit('step_end',{stepId:step(5),status:'blocked',summary:'依赖步骤失败'});
   const failed=inspect(step(4)),blocked=inspect(step(5));
   emit('step_start',{stepId:step(6)});
   const independent=inspect(step(6));
   emit('step_pending',{stepId:step(7),dependsOn:[step(1)]});
   emit('step_start',{stepId:step(7),dependsOn:[]});
   const cleared=inspect(step(7));
   emit('step_pending',{stepId:'late-dependent',displayName:'合并结果',dependsOn:['late-source']});
   const unresolved=inspect('late-dependent');
   emit('step_start',{stepId:'late-source',displayName:'补查预约信息'});
   emit('token',{stepId:'late-source',token:'预约信息'});
   const resolved=inspect('late-dependent'),namedSource=inspect('late-source');
   const malicious='<img src=x onerror="window.__dependencyXss=true">';
   emit('step_pending',{stepId:'safe-dependent',displayName:'展示资料',dependsOn:['unsafe-name-source']});
   emit('step_start',{stepId:'unsafe-name-source',displayName:malicious});
   const safe=inspect('safe-dependent');
   // The server can replay the same event or deliver another status update with
   // a new event ID. Neither may append a second dependency label.
   applyRunTimelineEvent(view,completedEvent.eventType,completedEvent.data,completedEvent.eventId,true);
   emit('step_end',{stepId:step(3),dependsOn:[step(1),step(2)]});
   snapshot.timelineEvents.push(completedEvent);
   const stateOfCards=()=>Object.keys(view.stepById).sort().map(id=>({id,...inspect(id)}));
   const live=stateOfCards();
   const liveCount=msg.querySelectorAll('.step-card').length;
   view=restoreTimelineSnapshot(msg,snapshot,'');
   const refreshed=stateOfCards(),refreshedCount=msg.querySelectorAll('.step-card').length;
   view=restoreTimelineSnapshot(msg,snapshot,'');
   return {pending,started,token,progress,completed,failed,blocked,independent,cleared,
    unresolved,resolved,namedSource,safe,malicious,live,refreshed,refreshedAgain:stateOfCards(),
    liveCount,refreshedCount,finalCount:msg.querySelectorAll('.step-card').length,
    unsafeImages:msg.querySelectorAll('.steps-container img').length,xss:window.__dependencyXss===true};
  });
  for(const phase of ['pending','started','token','progress','completed']){
   assert.deepEqual(dependencyResult[phase].badges,['依赖: 执行 第1步 / 执行 第2步'],`${phase} must retain the restored dependency label`);
  }
  assert.equal(dependencyResult.completed.status,'done');
  assert.equal(dependencyResult.completed.body,'完整攻略正文');
  assert.deepEqual(dependencyResult.failed.badges,['依赖: 执行 第3步']);
  assert.equal(dependencyResult.failed.status,'failed');
  assert.deepEqual(dependencyResult.blocked.badges,['依赖: 执行 第3步 / 执行 第4步']);
  assert.equal(dependencyResult.blocked.status,'blocked');
  assert.deepEqual(dependencyResult.independent.badges,[]);
  assert.deepEqual(dependencyResult.cleared.badges,[],'an explicit empty dependency array must remove the label');
  assert.deepEqual(dependencyResult.unresolved.badges,['依赖: late-source']);
  assert.deepEqual(dependencyResult.resolved.badges,['依赖: 补查预约信息'],'a late source name must update an existing dependent card');
  assert.equal(dependencyResult.namedSource.name,'补查预约信息','tokens without displayName must not reset the source label');
  assert.deepEqual(dependencyResult.safe.badges,[`依赖: ${dependencyResult.malicious}`]);
  assert.equal(dependencyResult.unsafeImages,0);assert.equal(dependencyResult.xss,false);
  assert.deepEqual(dependencyResult.refreshed,dependencyResult.live,'refresh must reconstruct the same names, dependencies, contents and statuses');
  assert.deepEqual(dependencyResult.refreshedAgain,dependencyResult.live,'repeated refresh must remain idempotent');
  assert.equal(dependencyResult.liveCount,dependencyResult.refreshedCount);
  assert.equal(dependencyResult.liveCount,dependencyResult.finalCount);
  const recoveredOutputResult=await page.evaluate(()=>{
   const rid='recovered-output-run',sid=state.sessionId,snapshot={runId:rid,sessionId:sid,timelineEvents:[]};
   let seq=0;
   const record=(eventType,data)=>({eventId:`500-${++seq}`,eventType,data:{runId:rid,sessionId:sid,...data}});
   const inherited={sourceRunId:'earlier-run',steps:[
    {ordinal:1,stepId:'flow_step4_execute_step_1',title:'已确认行程',content:'沿用的行程内容'},
    {ordinal:2,stepId:'flow_step4_execute_step_2',title:'已查天气',preview:'沿用的天气摘要'}
   ]};
   snapshot.timelineEvents.push(
    record('run_replay_context',inherited),record('run_replay_context',inherited),
    record('step_start',{stepId:'flow_step4_execute_step_3',displayName:'查询景点',dependsOn:['flow_step4_execute_step_1']}),
    record('message',{type:'execution',step:3,content:'查询执行完成：\n=== 执行结果 ===\n```json\n{"景点":"故宫","预约":"已查到"}\n```'}),
    record('step_end',{stepId:'flow_step4_execute_step_3'}),
    record('step_start',{stepId:'flow_step4_execute_step_4',displayName:'汇总攻略',dependsOn:['flow_step4_execute_step_3']}),
    record('token',{stepId:'flow_step4_execute_step_4',token:'已有的完整展示正文'}),
    record('message',{type:'execution',step:4,content:'=== 执行结果 ===\n不应覆盖已有正文'}),
    record('step_end',{stepId:'flow_step4_execute_step_4'}));
   const msg=addMessage('assistant','',false,null,rid);
   let view=restoreTimelineSnapshot(msg,snapshot,'');
   const inspect=()=>[...msg.querySelectorAll('.step-card')].map(card=>({
    name:card.querySelector('.step-name').textContent,
    status:card.querySelector('.step-status').textContent,
    body:card.querySelector('.step-body').textContent,
    badges:[...card.querySelectorAll('.step-dep')].map(b=>b.textContent),
    done:card.classList.contains('done')
   }));
   const restored=inspect();
   const inheritedEvent=record('run_replay_context',inherited);
   snapshot.timelineEvents.push(inheritedEvent);
   applyRunTimelineEvent(view,inheritedEvent.eventType,inheritedEvent.data,inheritedEvent.eventId,true);
   applyRunTimelineEvent(view,inheritedEvent.eventType,inheritedEvent.data,inheritedEvent.eventId,true);
   const executionEvent=record('message',{type:'execution',step:4,content:'=== 执行结果 ===\n仍不应覆盖已有正文'});
   snapshot.timelineEvents.push(executionEvent);
   applyRunTimelineEvent(view,executionEvent.eventType,executionEvent.data,executionEvent.eventId,true);
   const afterLive=inspect();
   view=restoreTimelineSnapshot(msg,snapshot,'');
   return {restored,afterLive,refreshed:inspect(),finalAnswer:view.finalAnswer.textContent};
  });
  assert.equal(recoveredOutputResult.restored.length,4,'two inherited steps and two executed steps must restore exactly once');
  assert.deepEqual(recoveredOutputResult.restored.slice(0,2).map(c=>({name:c.name,status:c.status,body:c.body,done:c.done})),[
   {name:'执行 第1步',status:'已沿用',body:'沿用的行程内容',done:true},
   {name:'执行 第2步',status:'已沿用',body:'沿用的天气摘要',done:true}
  ]);
  const structured=recoveredOutputResult.restored[2];
  assert.match(structured.body,/本步骤未单独生成展示正文/);
  assert.match(structured.body,/"景点":"故宫"/);assert.doesNotMatch(structured.body,/=== 执行结果 ===|```/);
  assert.deepEqual(structured.badges,['依赖: 执行 第1步']);assert.equal(structured.done,true);
  assert.equal(recoveredOutputResult.restored[3].body,'已有的完整展示正文','execution metadata must not overwrite streamed text');
  assert.deepEqual(recoveredOutputResult.afterLive,recoveredOutputResult.restored,'new replay-context events and execution metadata must not duplicate or overwrite restored cards');
  assert.deepEqual(recoveredOutputResult.refreshed,recoveredOutputResult.restored);
  assert.equal(recoveredOutputResult.finalAnswer,'','per-step execution output must stay in its step rather than become the final answer');
  const compactedReplayResult=await page.evaluate(()=>{
   const rid='compacted-replay-run',sid=state.sessionId,stepId='flow_step4_execute_step_2';
   const data=extra=>({runId:rid,sessionId:sid,stepId,...extra});
   const msg=addMessage('assistant','',false,null,rid);
   const view=restoreTimelineSnapshot(msg,{runId:rid,sessionId:sid,lastEventId:'600-5',timelineEvents:[
    {eventId:'600-1',eventType:'step_start',data:data({dependsOn:['flow_step4_execute_step_1']})},
    {eventId:'600-4',eventType:'token',data:data({token:'北京三日游'})}
   ]},'');
   for(const [id,token] of [['600-2','北京'],['600-3','三日'],['600-4','游']]){
    applyRunTimelineEvent(view,'token',data({token}),id,true);
   }
   const afterOldReplay=msg.querySelector('.step-body').textContent;
   // Independent live events may arrive out of ID order; snapshot coverage must
   // not turn into a moving cutoff that drops genuine post-snapshot content.
   applyRunTimelineEvent(view,'token',data({token:' 后续甲'}),'601-3',true);
   applyRunTimelineEvent(view,'token',data({token:' 后续乙'}),'601-1',true);
   return {afterOldReplay,afterLive:msg.querySelector('.step-body').textContent,
    cards:msg.querySelectorAll('.step-card').length,
    badges:[...msg.querySelectorAll('.step-dep')].map(b=>b.textContent),cursor:view.lastEventId};
  });
  assert.equal(compactedReplayResult.afterOldReplay,'北京三日游','raw token IDs merged into the snapshot must not append again');
  assert.equal(compactedReplayResult.afterLive,'北京三日游 后续甲 后续乙','genuine post-snapshot events must survive out-of-order arrival');
  assert.equal(compactedReplayResult.cards,1);assert.equal(compactedReplayResult.cursor,'601-3');
  assert.deepEqual(compactedReplayResult.badges,['依赖: 执行 第1步']);
  const lateContentResult=await page.evaluate(()=>{
   const rid='late-content-run',sid=state.sessionId,msg=addMessage('assistant','',false,null,rid);
   const snapshot={runId:rid,sessionId:sid,timelineEvents:[]};
   let view=restoreTimelineSnapshot(msg,snapshot,''),seq=0;
   const emit=(type,data)=>{
    const e={eventId:`700-${++seq}`,eventType:type,data:{runId:rid,sessionId:sid,...data}};
    snapshot.timelineEvents.push(e);applyRunTimelineEvent(view,e.eventType,e.data,e.eventId,true);
   };
   const inspect=stepId=>{
    const card=view.stepById[stepId].card;
    return {status:['running','done','failed','blocked'].find(s=>card.classList.contains(s)),
     label:card.querySelector('.step-status').textContent,body:card.querySelector('.step-body').textContent,
     badges:[...card.querySelectorAll('.step-dep')].map(b=>b.textContent)};
   };
   emit('step_start',{stepId:'streaming-step',displayName:'正在汇总'});
   applyRunTimelineEvent(view,'step_progress',{runId:rid,sessionId:sid,stepId:'streaming-step',phase:'model_streaming',waitSeconds:25,timestamp:100},'',true);
   const beforeToken=inspect('streaming-step');
   emit('token',{stepId:'streaming-step',token:'正在输出正文'});
   const afterToken=inspect('streaming-step');
   const terminal=[];
   for(const status of ['done','failed','blocked']){
    const stepId=`late-${status}`;
    emit('step_start',{stepId,displayName:`${status}步骤`,dependsOn:['flow_step4_execute_step_1']});
    emit('token',{stepId,token:'已有正文'});
    emit('step_end',{stepId,status,summary:`${status}终态说明`});
    const before=inspect(stepId);
    emit('token',{stepId,token:'，迟到的内容'});
    emit('token',{stepId,token:'[DONE]'});
    terminal.push({stepId,before,after:inspect(stepId)});
   }
   view=restoreTimelineSnapshot(msg,snapshot,'');
   return {beforeToken,afterToken,terminal,refreshed:terminal.map(t=>inspect(t.stepId)),cards:msg.querySelectorAll('.step-card').length};
  });
  assert.equal(lateContentResult.beforeToken.label,'模型正在生成 · 已等待 25 秒');
  assert.equal(lateContentResult.afterToken.label,lateContentResult.beforeToken.label,'a content chunk must not erase the current progress label');
  assert.equal(lateContentResult.afterToken.status,'running');
  assert.equal(lateContentResult.afterToken.body,'正在输出正文');
  for(const terminal of lateContentResult.terminal){
   assert.equal(terminal.after.status,terminal.before.status,`${terminal.stepId} must not be reactivated by a late content chunk`);
   assert.equal(terminal.after.label,terminal.before.label,`${terminal.stepId} must retain the terminal explanation`);
   assert.equal(terminal.after.body,'已有正文，迟到的内容','late content must still be retained without appending the DONE marker');
   assert.deepEqual(terminal.after.badges,['依赖: 执行 第1步']);
  }
  assert.deepEqual(lateContentResult.refreshed,lateContentResult.terminal.map(t=>t.after),'refresh must preserve terminal status even when the saved final event is a token');
  assert.equal(lateContentResult.cards,4);
  const retrievalResult=await page.evaluate(()=>{
   const rid='retrieval-progress-run',sid=state.sessionId,msg=addMessage('assistant','',false,null,rid);
   const view=restoreTimelineSnapshot(msg,{runId:rid,sessionId:sid,timelineEvents:[]},'');
   const emit=(type,data,id='')=>applyRunTimelineEvent(view,type,{runId:rid,sessionId:sid,stepId:'retrieve',...data},id,true);
   emit('step_start',{displayName:'检索资料'},'800-1');
   const cursor=msg._runEventLedger.lastEventId,states=[];
   for(const [index,phase] of ['memory_retrieval','rag_routing','knowledge_retrieval','knowledge_reranking','preparing_model'].entries()){
    emit('step_progress',{phase,waitSeconds:0,timestamp:index*10+1,message:'<img src=x onerror="window.__phaseXss=true">'});
    const fresh=msg.querySelector('.step-status').textContent;
    emit('step_progress',{phase,waitSeconds:15,timestamp:index*10+2});
    states.push({phase,fresh,waiting:msg.querySelector('.step-status').textContent});
   }
   const beforeUnknown=msg.querySelector('.step-status').textContent;
   for(const phase of ['unknown','toString','__proto__'])emit('step_progress',{phase,message:'错误消息不得展示',waitSeconds:90,timestamp:99});
   const afterUnknown=msg.querySelector('.step-status').textContent;
   emit('step_progress',{phase:'memory_retrieval',waitSeconds:99,timestamp:1});
   const afterLate=msg.querySelector('.step-status').textContent;
   const cursorAfter=msg._runEventLedger.lastEventId;
   emit('step_end',{status:'failed',summary:'检索失败'},'800-2');
   emit('step_progress',{phase:'knowledge_retrieval',waitSeconds:20,timestamp:100});
   return {states,beforeUnknown,afterUnknown,afterLate,cursor,cursorAfter,terminal:msg.querySelector('.step-status').textContent,
    failed:msg.querySelector('.step-card').classList.contains('failed'),images:msg.querySelectorAll('img').length,xss:window.__phaseXss===true};
  });
  const retrievalLabels={memory_retrieval:'正在检索记忆',rag_routing:'正在判断知识库检索策略',knowledge_retrieval:'正在检索知识库',knowledge_reranking:'正在筛选检索结果',preparing_model:'正在准备模型请求'};
  for(const phase of retrievalResult.states){
   assert.equal(phase.fresh,retrievalLabels[phase.phase],'switching phase must discard the previous phase elapsed time');
   assert.equal(phase.waiting,`${retrievalLabels[phase.phase]} · 已等待 15 秒`);
  }
  assert.equal(retrievalResult.beforeUnknown,retrievalResult.afterUnknown);assert.equal(retrievalResult.beforeUnknown,retrievalResult.afterLate);
  assert.equal(retrievalResult.cursor,retrievalResult.cursorAfter);assert.equal(retrievalResult.terminal,'检索失败');
  assert.equal(retrievalResult.failed,true);assert.equal(retrievalResult.images,0);assert.equal(retrievalResult.xss,false);
  const preparationResult=await page.evaluate(async()=>{
   const rid='preparation-progress-run',sid=state.sessionId,msg=addMessage('assistant','',false,null,rid);
   const view=restoreTimelineSnapshot(msg,{runId:rid,sessionId:sid,timelineEvents:[]},'');
   const emit=(phase,extra={})=>applyRunTimelineEvent(view,'run_progress',{runId:rid,sessionId:sid,phase,timestamp:10,...extra},'',true);
   const label=()=>msg.querySelector('.run-preparation-status')?.textContent||'';
   emit('agent_routing',{waitSeconds:10});const routing=label();
   emit('agent_preparing',{waitSeconds:0,timestamp:20});const preparing=label();
   const resource='<img src=x onerror="window.__prepareXss=true"> 高德地图';
   emit('mcp_connecting',{resourceName:resource,waitSeconds:12,timestamp:30,message:'不能显示的后端错误'});
   const connecting=label(),copiedAnswer=extractFinalAnswerText(msg),persistedHtml=persistentRunContentHtml(msg.querySelector('.msg-content'));
   for(const phase of ['unknown','toString','__proto__'])emit(phase,{timestamp:40});
   emit('agent_preparing',{timestamp:25});emit('agent_preparing',{runId:'another-run',timestamp:40});
   emit('agent_preparing',{sessionId:'another-session',timestamp:40});
   const unchanged=label(),rows=msg.querySelectorAll('.run-preparation-status').length,
    fakeSteps=msg.querySelectorAll('.step-card').length,images=msg.querySelectorAll('img').length,cursor=msg._runEventLedger.lastEventId;
   emit('run_ready',{timestamp:40});const readyCount=msg.querySelectorAll('.run-preparation-status').length;
   emit('mcp_connecting',{timestamp:50});const afterReady=label();
   const closed=[];
   for(const reason of ['step_start','complete','error','cancelled','plan_failed']){
    const run=`preparation-${reason}`,node=addMessage('assistant','',false,null,run),v=restoreTimelineSnapshot(node,{runId:run,sessionId:sid,timelineEvents:[]},'');
    const event=(type,data,id='')=>applyRunTimelineEvent(v,type,{runId:run,sessionId:sid,...data},id,true);
    event('run_progress',{phase:'agent_preparing',timestamp:1});
    if(reason==='step_start')event('step_start',{stepId:'execute',displayName:'执行'},'801-1');
    else if(reason==='cancelled')appendRestoredCancelledMarker(node);
    else if(reason==='plan_failed')event('plan_review_status',{status:'FAILED'},'801-1');
    else event('message',{type:reason,content:reason==='error'?'请求失败':''},'801-1');
    event('run_progress',{phase:'mcp_connecting',timestamp:2});
    closed.push({reason,count:node.querySelectorAll('.run-preparation-status').length});
   }
   const legacyRun='legacy-preparation-run',legacy=addMessage('assistant','',false,null,legacyRun);
   const legacyFrame=(type,data)=>`event: ${type}\ndata: ${JSON.stringify({runId:legacyRun,sessionId:sid,...data})}\n\n`;
   await consumeRestoredPlanSse(new Response(legacyFrame('run_progress',{phase:'mcp_connecting',resourceName:'天气服务',waitSeconds:20,timestamp:1})),legacy,null,{runId:legacyRun,sessionId:sid});
   const legacyLabel=legacy.querySelector('.run-preparation-status')?.textContent;
   await consumeRestoredPlanSse(new Response(legacyFrame('step_start',{stepId:'retrieve',displayName:'工具分析'})+legacyFrame('step_progress',{stepId:'retrieve',phase:'memory_retrieval',waitSeconds:15,timestamp:2})),legacy,null,{runId:legacyRun,sessionId:sid});
   const legacyStepLabel=legacy.querySelector('.step-status')?.textContent;
   await consumeRestoredPlanSse(new Response(legacyFrame('message',{type:'error',content:'连接失败'})+legacyFrame('run_progress',{phase:'mcp_connecting',timestamp:2})),legacy,null,{runId:legacyRun,sessionId:sid});
   const terminalSnapshotRun='terminal-preparation-run',terminalSnapshot=addMessage('assistant','',false,null,terminalSnapshotRun);
   const terminalView=restoreTimelineSnapshot(terminalSnapshot,{runId:terminalSnapshotRun,sessionId:sid,status:'COMPLETED',timelineEvents:[]},'已完成');
   applyRunTimelineEvent(terminalView,'run_progress',{runId:terminalSnapshotRun,sessionId:sid,phase:'mcp_connecting',timestamp:1},'',true);
   return {routing,preparing,connecting,resource,unchanged,rows,fakeSteps,images,cursor,copiedAnswer,persistedHtml,readyCount,afterReady,closed,
    legacyLabel,legacyStepLabel,legacyClosed:legacy.querySelectorAll('.run-preparation-status').length,
    terminalSnapshotCount:terminalSnapshot.querySelectorAll('.run-preparation-status').length,xss:window.__prepareXss===true};
  });
  assert.equal(preparationResult.routing,'正在选择 Agent · 已等待 10 秒');assert.equal(preparationResult.preparing,'正在准备 Agent');
  assert.equal(preparationResult.connecting,`正在连接 MCP 工具：${preparationResult.resource} · 已等待 12 秒`);
  assert.equal(preparationResult.unchanged,preparationResult.connecting,'foreign, unknown and late progress must not alter preparation state');
  assert.equal(preparationResult.rows,1);assert.equal(preparationResult.fakeSteps,0);assert.equal(preparationResult.images,0);assert.equal(preparationResult.xss,false);
  assert.equal(preparationResult.cursor,null);assert.equal(preparationResult.copiedAnswer,'','preparation status must not become saved or copied answer text');
  assert.doesNotMatch(preparationResult.persistedHtml,/run-preparation-status|正在连接 MCP/,'persisted plan-review HTML must exclude transient waiting state');
  assert.equal(preparationResult.readyCount,0);assert.equal(preparationResult.afterReady,'');
  for(const closed of preparationResult.closed)assert.equal(closed.count,0,`${closed.reason} must prevent stale preparation indicators`);
  assert.equal(preparationResult.legacyLabel,'正在连接 MCP 工具：天气服务 · 已等待 20 秒');assert.equal(preparationResult.legacyClosed,0);
  assert.equal(preparationResult.legacyStepLabel,'正在检索记忆 · 已等待 15 秒');
  assert.equal(preparationResult.terminalSnapshotCount,0);
  await page.evaluate(()=>{state.currentAgentId='a1';document.getElementById('msgInput').value='fixture preparation progress';void sendMessage();});
  await page.waitForFunction(()=>[...document.querySelectorAll('.run-preparation-status')].some(e=>e.textContent==='正在连接 MCP 工具：高德地图 · 已等待 12 秒'));
  assert.ok(preparationResponse,'initial SSE fixture must be open while preparation is visible');
  assert.equal(await page.locator(`[data-run-id="${runId}"] .step-card`).count(),0);
  preparationResponse.write(frame('','step_start',payload({stepId:'analysis',displayName:'工具分析'}))+frame('','step_progress',payload({stepId:'analysis',phase:'knowledge_retrieval',waitSeconds:15,timestamp:110})));
  await page.waitForFunction(()=>[...document.querySelectorAll('.step-status')].some(e=>e.textContent==='正在检索知识库 · 已等待 15 秒'));
  assert.equal(await page.locator(`[data-run-id="${runId}"] .run-preparation-status`).count(),0,'initial SSE must clear preparation as execution starts');
  preparationResponse.end(frame('','message',payload({type:'complete'})));
  preparationResponse=null;
  await page.waitForFunction(()=>!state.sending);
  assert.equal(await page.locator(`[data-run-id="${runId}"] .run-preparation-status`).count(),0,'initial SSE must clear preparation as execution starts');
  assert.deepEqual(errors,[]);
  if(process.env.PREPARATION_SCREENSHOT_PATH||process.env.RAG_SCREENSHOT_PATH){
   await page.setViewportSize({width:390,height:844});
   await page.evaluate(()=>{
    document.getElementById('messageArea').innerHTML='';
    const sid=state.sessionId,rid='preparation-mobile-preview',msg=addMessage('assistant','',false,'旅行规划助手',rid);
    const view=restoreTimelineSnapshot(msg,{runId:rid,sessionId:sid,timelineEvents:[]},'');
    applyRunTimelineEvent(view,'run_progress',{runId:rid,sessionId:sid,phase:'mcp_connecting',resourceName:'用于行程规划的高德地图服务（交通路线、景点查询与地点解析）',waitSeconds:12,timestamp:1},'',true);
    const ragRun='rag-mobile-preview',rag=addMessage('assistant','',false,'知识库助手',ragRun);
    const ragView=restoreTimelineSnapshot(rag,{runId:ragRun,sessionId:sid,timelineEvents:[]},'');
    applyRunTimelineEvent(ragView,'step_start',{runId:ragRun,sessionId:sid,stepId:'analysis',displayName:'MCP 工具分析'},'900-1',true);
    applyRunTimelineEvent(ragView,'step_progress',{runId:ragRun,sessionId:sid,stepId:'analysis',phase:'knowledge_retrieval',waitSeconds:65,timestamp:1},'',true);
   });
   const mobile=await page.locator('[data-run-id="preparation-mobile-preview"] .run-preparation-status').evaluate(el=>({count:el.parentElement.querySelectorAll('.run-preparation-status').length,width:el.getBoundingClientRect().width,viewport:innerWidth,overflow:el.scrollWidth>el.clientWidth+1}));
   assert.equal(mobile.count,1);assert.ok(mobile.width<=mobile.viewport);assert.equal(mobile.overflow,false,'a long MCP display name must wrap on mobile');
   if(process.env.PREPARATION_SCREENSHOT_PATH)await page.locator('[data-run-id="preparation-mobile-preview"] .msg-content').screenshot({path:process.env.PREPARATION_SCREENSHOT_PATH,animations:'disabled'});
   if(process.env.RAG_SCREENSHOT_PATH)await page.locator('[data-run-id="rag-mobile-preview"] .msg-content').screenshot({path:process.env.RAG_SCREENSHOT_PATH,animations:'disabled'});
  }
  if(process.env.PROGRESS_SCREENSHOT_PATH){
   await page.evaluate(()=>{
    document.getElementById('messageArea').innerHTML='';
    const rid='progress-preview',sid=state.sessionId,msg=addMessage('assistant','',false,'旅行规划助手',rid);
    const view=restoreTimelineSnapshot(msg,{runId:rid,sessionId:sid,timelineEvents:[]},'');
    for(const [stepId,displayName,phase,waitSeconds] of [
     ['one','行程建议','awaiting_model',20],['two','查询景点信息','tool_running',12],['three','确认出行日期','awaiting_user',30]]){
     applyRunTimelineEvent(view,'step_start',{runId:rid,sessionId:sid,stepId,displayName},'',true);
     applyRunTimelineEvent(view,'step_progress',{runId:rid,sessionId:sid,stepId,phase,waitSeconds,timestamp:1},'',true);
    }
   });
   await page.locator('[data-run-id="progress-preview"] .steps-container').screenshot({path:process.env.PROGRESS_SCREENSHOT_PATH,animations:'disabled'});
  }
  console.log('PASS replay/identity/dependencies, inherited and structured output, compact snapshots, late tokens, retrieval/model preparation phases, run preparation in initial/legacy/timeline SSE, safe transient status and terminal cleanup');
 }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);process.exitCode=1;server.close();});
