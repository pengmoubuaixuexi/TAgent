(() => {
  'use strict';
  const $=id=>document.getElementById(id), size=20;
  let offset=0, selected=new URLSearchParams(location.search).get('runId'), request=0;
  const labels={RUNNING:'运行中',BLOCKED:'等待计划确认',COMPLETED:'已完成',FAILED:'执行失败',CANCELLED:'已取消',INTERRUPTED:'进程中断'};
  const time=value=>value?new Date(value).toLocaleString('zh-CN'):'—';
  const element=(tag,text,className)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;if(className)e.className=className;return e;};
  async function api(path){const response=await fetch(path,{credentials:'same-origin',headers:{Accept:'application/json'}});if(response.status===401){throw new Error('请先登录后查看运行历史。');}const body=await response.json();if(!response.ok||body.code!=='0000')throw new Error(body.info||'读取运行记录失败');return body.data;}
  async function load(){
    $('notice').textContent='加载中…';$('refresh').disabled=true;
    try {const rows=await api(`/api/v1/history?offset=${offset}&limit=${size}`);$('runs').replaceChildren();
      for(const row of rows){const b=element('button',null,'run'+(row.runId===selected?' active':''));b.dataset.runId=row.runId;b.append(element('strong',row.question||'空请求'),element('small',`${labels[row.status]||row.status} · ${row.strategy||'路由中'}${row.agentVersion?' · v'+row.agentVersion:''}`),element('small',time(row.createdAt)));b.onclick=()=>show(row.runId);$('runs').append(b);}
      if(!rows.length)$('runs').append(element('p','暂无运行记录。'));
      $('previous').disabled=offset===0;$('next').disabled=rows.length<size;$('page').textContent=`第 ${offset/size+1} 页`;$('notice').textContent='';
    }catch(e){$('notice').textContent=e.message;}finally{$('refresh').disabled=false;}
  }
  async function show(id){
    selected=id;const sequence=++request;$('notice').textContent='读取详情…';
    for(const button of document.querySelectorAll('.run'))button.classList.toggle('active',button.dataset.runId===id);
    try {const row=await api(`/api/v1/history/${encodeURIComponent(id)}`);if(sequence!==request)return;
      const box=$('detail');box.replaceChildren(element('h2',labels[row.status]||row.status));const dl=element('dl');
      for(const [key,value] of [['模式',row.strategy||'路由中'],['配置版本',row.agentVersion?'v'+row.agentVersion:'未绑定'],['开始时间',time(row.createdAt)],['更新时间',time(row.updatedAt)]])dl.append(element('dt',key),element('dd',value));box.append(dl);
      const link=element('a','保存产物 / 提交反馈');link.href='/outcomes.html?runId='+encodeURIComponent(id);box.append(link);
      const usage=element('a','查看本次用量');usage.href='/usage.html?runId='+encodeURIComponent(id);box.append(document.createTextNode(' · '),usage);
      const improve=element('div');improve.className='improve';box.append(improve);improvements(id,improve,row);
      box.append(element('h2','原始问题'),element('pre',row.question||''));
      if(row.answer)box.append(element('h2','最终回答'),element('pre',row.answer));
      if(row.error)box.append(element('p',row.error,'error'));
      box.append(element('h2','执行诊断'));
      if(row.eventsTruncated)box.append(element('p','诊断记录达到保存上限，仅保留部分内容。','muted'));
      for(const event of row.events||[]){const d=element('details');d.append(element('summary',`${event.eventType} · ${time(event.createdAt)}`),element('pre',typeof event.payload==='string'?event.payload:JSON.stringify(event.payload,null,2)));box.append(d);}
      box.append(element('h2','完整轨迹'),element('p','每次模型请求（含失败和重试）的完整输入、思考与输出，每次工具调用的参数和完整结果，以及关键运行事件。显示时按脱敏规则处理。','muted'));
      const traceBox=element('div');traceBox.className='trace';const load=element('button','加载完整轨迹');load.onclick=()=>trace(id,traceBox,load);box.append(load,traceBox);
      const meta=element('details');meta.append(element('summary','运行标识'),element('pre',id));box.append(meta);
      const seq=new URLSearchParams(location.search).get('seq');
      history.replaceState(null,'','?runId='+encodeURIComponent(id));$('notice').textContent='';
      if(seq)trace(id,traceBox,load,Number(seq));
    }catch(e){if(sequence===request)$('notice').textContent=e.message;}
  }
  const kinds={MODEL:'模型',TOOL:'工具',EVENT:'事件'},roles={system:'系统提示',user:'用户',assistant:'模型',tool:'工具结果'};
  const seconds=ms=>ms==null?'':(ms/1000).toFixed(1)+' 秒';
  const pre=text=>element('pre',text==null?'':typeof text==='string'?text:JSON.stringify(text,null,2));
  const contentText=content=>Array.isArray(content)?content.map(p=>p.type==='text'?p.text:p.type==='image_url'?'[图片]':JSON.stringify(p)).join('\n'):content==null?'':typeof content==='string'?content:JSON.stringify(content,null,2);
  // Review this run: lists earlier suggestions, or starts a new one and opens it.
  async function improvements(id,box,row){
    try{const list=await api(`/api/v1/improvements?runId=${encodeURIComponent(id)}`);if(selected!==id)return;
      const start=element('button','分析并提出改进');start.title='复盘这次运行：判断是否外部故障，定位出问题的节点并给出修改建议（不会自动修改 Agent）';
      start.onclick=async()=>{start.disabled=true;$('notice').textContent='正在发起复盘…';
        try{const response=await fetch('/api/v1/improvements',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json',Accept:'application/json'},body:JSON.stringify({runId:id})});
          const body=await response.json();if(!response.ok||body.code!=='0000')throw new Error(body.info||'发起失败');location.href='/improvements.html?id='+encodeURIComponent(body.data.suggestionId);}
        catch(e){$('notice').textContent=e.message;start.disabled=false;}};
      if(row.agentId||row.strategy)box.append(start);
      for(const s of list){const a=element('a',`改进建议 · ${s.diagnosis?.summary||s.status}`);a.href='/improvements.html?id='+encodeURIComponent(s.suggestionId);box.append(document.createTextNode(' '),a);}
    }catch(_){/* Suggestions are optional on this page. */}
  }
  async function trace(id,box,button,openSeq){
    button.disabled=true;box.replaceChildren(element('p','读取轨迹…','muted'));
    try{const data=await api(`/api/v1/history/${encodeURIComponent(id)}/trace`);if(selected!==id)return;box.replaceChildren();
      const nodes=data.nodes||[];if(!nodes.length){box.append(element('p','这次运行没有轨迹记录（轨迹功能上线前的运行，或已按保留期清理）。','muted'));return;}
      const names={};for(const n of nodes)if(n.kind==='EVENT'&&n.summary?.stepId&&n.summary?.displayName)names[n.summary.stepId]=n.summary.displayName;
      const models=nodes.filter(n=>n.kind==='MODEL'),failed=models.filter(n=>n.status!=='OK').length;
      box.append(element('p',`共 ${nodes.length} 条：模型请求 ${models.length} 次${failed?`（未成功 ${failed} 次）`:''}，工具调用 ${nodes.filter(n=>n.kind==='TOOL').length} 次，事件 ${nodes.filter(n=>n.kind==='EVENT').length} 条。`,'muted'));
      let step=null;
      for(const n of nodes){
        const label=names[n.step]||(n.step==='CHAT_HELPER'?'辅助模型请求（检索判断、记忆抽取等）':n.step)||'运行';
        if(label!==step&&n.kind!=='EVENT'){step=label;box.append(element('h3',label,'trace-step'));}
        const d=element('details');d.className='trace-node'+(n.status&&!['OK','success'].includes(n.status)&&n.kind!=='EVENT'?' failed':'');
        const s=n.summary||{},parts=[`#${n.seq}`,kinds[n.kind]||n.kind,n.name||''];
        if(n.kind==='MODEL'){parts.push(n.status==='OK'?'成功':n.status==='CANCELLED'?'已中止':'失败',seconds(n.durationMs));if(n.inputTokens!=null)parts.push(`输入 ${n.inputTokens} / 输出 ${n.outputTokens??0} Token`);
          if(s.toolCalls)parts.push('调用 '+s.toolCalls.join('、'));else if(s.reply)parts.push(s.reply);if(s.error)parts.push(s.error);}
        else if(n.kind==='TOOL'){parts.push(n.status||'',seconds(n.durationMs),'参数 '+(s.arguments||''));if(s.error)parts.push(s.error);}
        else parts.push(eventText(n.name,s));
        d.append(element('summary',parts.filter(Boolean).join(' · ')));
        d.ontoggle=async()=>{if(!d.open||d.dataset.loaded)return;d.dataset.loaded='1';const body=element('div');body.append(element('p','读取…','muted'));d.append(body);
          try{renderNode(n,await api(`/api/v1/history/${encodeURIComponent(id)}/trace/${n.seq}`),body);}catch(e){body.replaceChildren(element('p',e.message,'error'));delete d.dataset.loaded;}};
        box.append(d);
        if(openSeq===n.seq){d.open=true;d.dispatchEvent(new Event('toggle'));d.scrollIntoView({block:'center'});}
      }
    }catch(e){box.replaceChildren(element('p',e.message,'error'));}finally{button.disabled=false;}
  }
  function eventText(type,s){
    if(type==='message')return {summary:'最终回答',complete:'运行完成',error:'运行出错',cancelled:'已取消'}[s.type]||s.type||'';
    if(type==='run_budget'){try{const b=JSON.parse(s.data);return `用量：已确认 ${b.confirmedTokens??'?'} Token，模型调用 ${b.modelCalls??'?'} 次，工具调用 ${b.toolAttempts??0} 次`;}catch(_){return s.data||'';}}
    return s.notice||s.summary||s.displayName||s.agentName||s.type||s.data||'';
  }
  function renderNode(n,p,body){
    body.replaceChildren();
    if(n.kind==='MODEL'){
      const r=p.request||{},reply=p.reply||{},dl=element('dl');
      for(const [k,v] of [['模型',reply.model||r.model],['连接',n.apiId],['用途',p.purpose],['HTTP 状态',p.httpStatus||'未收到响应'],['结束原因',reply.finishReason],['耗时',seconds(n.durationMs)]])if(v!=null&&v!=='')dl.append(element('dt',k),element('dd',String(v)));
      body.append(dl);
      if(r.incomplete)body.append(element('p',r.incomplete,'error'));
      const messages=r.messages||[],recent=messages.slice(-4),earlier=messages.slice(0,-4);
      body.append(element('h4',`输入消息（共 ${messages.length} 条）`));
      const render=(m,target)=>{target.append(element('h5',roles[m.role]||m.role));const text=contentText(m.content);if(text)target.append(pre(text));
        for(const c of m.tool_calls||[])target.append(pre(`调用工具 ${c.function?.name||''}：${c.function?.arguments||''}`));if(m.reasoning_content)target.append(pre('（回传的思考内容）\n'+m.reasoning_content));};
      if(earlier.length){const more=element('details');more.append(element('summary',`更早的 ${earlier.length} 条消息`));earlier.forEach(m=>render(m,more));body.append(more);}
      recent.forEach(m=>render(m,body));
      if((r.toolNames||[]).length)body.append(element('h4','可用工具'),element('p',r.toolNames.join('、')));
      if(reply.reasoning){const t=element('details');t.append(element('summary','思考内容'),pre(reply.reasoning));body.append(t);}
      if(reply.content)body.append(element('h4','输出'),pre(reply.content));
      if((reply.toolCalls||[]).length){body.append(element('h4','请求调用的工具'));for(const c of reply.toolCalls)body.append(pre(`${c.function?.name||''}：${c.function?.arguments||''}`));}
      if(p.error)body.append(element('h4','错误'),pre(p.error));
      const params={...r};delete params.messages;delete params.tools;delete params.toolNames;delete params.messageCount;
      const more=element('details');more.append(element('summary','请求参数与工具定义'),pre(params),pre(r.tools||[]));body.append(more);
    }else if(n.kind==='TOOL'){
      const dl=element('dl');
      for(const [k,v] of [['来源',p.source==='META'?'平台工具':'业务工具'],['步骤',p.stepLabel],['状态',n.status],['耗时',seconds(n.durationMs)],['结果长度',p.resultChars!=null?p.resultChars+' 字符':null]])if(v)dl.append(element('dt',k),element('dd',String(v)));
      body.append(dl,element('h4','参数'),pre(p.arguments),element('h4','完整结果'),pre(p.result));
      if(p.error)body.append(element('h4','错误'),pre(p.error));
    }else body.append(pre(p.data));
  }
  $('refresh').onclick=async()=>{await load();if(selected)await show(selected);};
  $('previous').onclick=()=>{offset=Math.max(0,offset-size);load();};$('next').onclick=()=>{offset+=size;load();};
  load();if(selected)show(selected);
})();
