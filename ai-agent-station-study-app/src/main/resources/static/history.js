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
      box.append(element('h2','原始问题'),element('pre',row.question||''));
      if(row.answer)box.append(element('h2','最终回答'),element('pre',row.answer));
      if(row.error)box.append(element('p',row.error,'error'));
      box.append(element('h2','执行诊断'));
      if(row.eventsTruncated)box.append(element('p','诊断记录达到保存上限，仅保留部分内容。','muted'));
      for(const event of row.events||[]){const d=element('details');d.append(element('summary',`${event.eventType} · ${time(event.createdAt)}`),element('pre',typeof event.payload==='string'?event.payload:JSON.stringify(event.payload,null,2)));box.append(d);}
      const meta=element('details');meta.append(element('summary','运行标识'),element('pre',id));box.append(meta);
      history.replaceState(null,'','?runId='+encodeURIComponent(id));$('notice').textContent='';
    }catch(e){if(sequence===request)$('notice').textContent=e.message;}
  }
  $('refresh').onclick=async()=>{await load();if(selected)await show(selected);};
  $('previous').onclick=()=>{offset=Math.max(0,offset-size);load();};$('next').onclick=()=>{offset+=size;load();};
  load();if(selected)show(selected);
})();
