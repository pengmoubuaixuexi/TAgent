(() => {
  'use strict';
  const $=id=>document.getElementById(id), api='/api/v1/knowledge';
  const labels={QUEUED:'等待处理',PROCESSING:'处理中',VERIFYING:'校验原件',CLEANING:'清理上次未完成的片段',PARSING:'提取与切分文本',ENRICHING:'生成上下文并写入索引',VECTORIZING:'写入向量索引',INDEXING:'写入关键词索引',PUBLISHING:'发布文件目录',SUCCEEDED:'已入库',FAILED:'入库失败',CANCEL_REQUESTED:'正在停止入库',CANCELLING:'正在停止入库',DELETING:'正在清理',DELETE_FAILED:'等待清理 / 删除失败',DELETED:'已删除'};
  let timer, loading=false, reloadRequested=false, previewSequence=0, preview={id:null,page:0,total:0};
  const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;};
  function notice(text,error=false){$('notice').textContent=text;$('notice').className='notice'+(error?' error':'');}
  async function request(path,options={}){
    const response=await fetch(path.startsWith('/api/')?path:api+path,{cache:'no-store',...options,headers:{Accept:'application/json',...options.headers}});
    let body;try{body=await response.json();}catch(_){throw new Error('请求失败（HTTP '+response.status+'）');}
    if(!response.ok||body.code!=='0000')throw new Error(body.info||'操作未完成');
    return body.data;
  }
  const send=(path,method,body)=>request(path,{method,...(body?{headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{})});
  function button(text,fn,danger=false){const b=el('button',text,danger?'danger':'');b.type='button';b.addEventListener('click',async()=>{b.disabled=true;try{await fn();}catch(e){notice(e.message,true);}finally{b.disabled=false;}});return b;}
  function renderQuota(q){
    const node=$('quota');if(!q){node.textContent='';return;}
    if(q.unlimited){node.textContent='管理员账户不受知识库配额限制';return;}
    const mb=b=>(b/1048576).toFixed(1);
    node.textContent=`文件 ${q.files}/${q.maxFiles} · 空间 ${mb(q.bytes)}/${mb(q.maxBytes)} MB · 今日入库 ${q.today}/${q.dailyIngestions} 次 · 排队中 ${q.pending}/${q.maxPending}`;
    node.title=`单个文件最多切分 ${q.maxParentsPerFile} 个段落；重试也计入每日次数，北京时间零点重置。`;
  }
  function render(data){
    renderQuota(data.quota);
    const rows=$('file-list');rows.replaceChildren();
    const represented=new Set(data.tasks.map(t=>t.fileId));
    const files=[...data.tasks,...data.files.filter(f=>!represented.has(f.ragId)).map(f=>({...f,fileId:f.ragId,status:'SUCCEEDED',stage:'SUCCEEDED',legacy:true}))];
    const tags=[...new Set(data.files.filter(f=>Number(f.status)===1).map(f=>f.knowledgeTag))].sort();
    const selected=$('search-tag').value;$('search-tag').replaceChildren(new Option('请选择知识库',''));
    $('knowledge-tags').replaceChildren();for(const tag of tags){$('search-tag').add(new Option(tag,tag));$('knowledge-tags').append(new Option(tag,tag));}
    if(tags.includes(selected))$('search-tag').value=selected;
    if(!files.length){const tr=el('tr'),td=el('td','还没有资料，上传第一个文件开始使用。','empty');td.colSpan=4;tr.append(td);rows.append(tr);}
    for(const file of files){
      const tr=el('tr'),identity=el('td'),state=el('td'),counts=el('td'),actions=el('td');
      identity.append(el('strong',file.fileName),el('div',file.knowledgeTag,'tag'));
      if(file.fileSize!=null)identity.append(el('div',(file.fileSize/1024).toFixed(1)+' KB','tag'));
      state.append(el('div',labels[file.status]||file.status,'state '+file.status));
      if(file.status==='PROCESSING'){state.append(el('div',labels[file.stage]||'处理中','tag'));const p=el('progress');p.max=100;p.value=file.progress||0;p.setAttribute('aria-label','入库进度');state.append(p);}
      if(file.error)state.append(el('div',file.error,'row-error'));
      if(file.warning)state.append(el('div',file.warning,'row-warning'));
      if(file.updatedAt)state.append(el('div',new Date(file.updatedAt).toLocaleString(),'tag'));
      counts.append(el('div',file.chunkCount?file.chunkCount+' 个片段':'—'),el('div',file.attempt?'第 '+file.attempt+' 次处理':file.legacy?'已有文件':'尚未执行','tag'));
      const group=el('div',undefined,'actions');
      if(!['QUEUED','DELETED'].includes(file.status))group.append(button('查看片段',()=>openPreview(file.fileId)));
      if(file.status==='FAILED')group.append(button('重试入库',async()=>{if(!confirm('将使用保留的原件重新入库，可能产生模型用量。继续吗？'))return;await send('/tasks/'+encodeURIComponent(file.taskId)+'/retry','POST');notice('已提交重试。');await load();}));
      const deleting=['DELETING','CANCEL_REQUESTED'].includes(file.status);
      if(!deleting)group.append(button(file.status==='PROCESSING'?'停止并删除':'删除',async()=>{
        if(!confirm(file.status==='PROCESSING'?'先停止入库，停止后再点击删除清理片段和原件。继续吗？':'将删除此文件的向量、关键词索引、目录及保留原件。继续吗？'))return;
        await send(file.taskId?'/tasks/'+encodeURIComponent(file.taskId):'/files/'+encodeURIComponent(file.fileId),'DELETE');
        notice('已提交操作，请查看文件的最新状态。');await load();
      },true));
      actions.append(group);tr.append(identity,state,counts,actions);rows.append(tr);
    }
    const pending=data.tasks.some(t=>['QUEUED','PROCESSING','CANCEL_REQUESTED','DELETING'].includes(t.status));
    $('poll-state').textContent=pending?'每 5 秒更新进度':'已刷新';
    clearTimeout(timer);if(pending)timer=setTimeout(()=>load(false),5000);
  }
  async function load(show=true){if(loading){reloadRequested=true;return;}loading=true;try{render(await request(''));if(show)notice('文件与任务已更新。');}catch(e){notice(e.message,true);clearTimeout(timer);}finally{loading=false;if(reloadRequested){reloadRequested=false;load(false);}}}
  $('refresh').addEventListener('click',()=>load());
  $('upload-form').addEventListener('submit',async event=>{
    event.preventDefault();const selected=[...$('files').files],tag=$('knowledge-tag').value.trim();if(!selected.length||!tag)return;
    $('upload').disabled=true;try{for(let i=0;i<selected.length;i++){
      $('upload-progress').textContent='正在保存原件 '+(i+1)+' / '+selected.length;
      const form=new FormData();form.append('knowledgeTag',tag);form.append('file',selected[i]);await request('/uploads',{method:'POST',body:form});
    }$('files').value='';notice('原件已保存，入库任务已提交。完成后可在 Agent 的知识库配置中选择此标签。');await load(false);
    }catch(e){notice(e.message,true);await load(false);}finally{$('upload').disabled=false;$('upload-progress').textContent='';}
  });
  async function openPreview(id,page=0){
    const sequence=++previewSequence;$('previous-page').disabled=true;$('next-page').disabled=true;
    preview={...preview,id,page};$('preview-title').textContent='文件片段';$('preview-state').textContent='正在读取…';$('preview-chunks').replaceChildren();
    if(!$('preview').open)$('preview').showModal();
    try{const data=await request('/files/'+encodeURIComponent(id)+'/chunks?page='+page);if(sequence!==previewSequence)return;preview.total=data.total;
      $('preview-title').textContent=data.fileName;$('preview-state').textContent=data.knowledgeTag+' · 共 '+data.total+' 个已存储片段（预览不调用模型）';
      snippets($('preview-chunks'),data.chunks);$('preview-page').textContent='第 '+(page+1)+' / '+Math.max(1,Math.ceil(data.total/data.pageSize))+' 页';
      $('previous-page').disabled=page===0;$('next-page').disabled=(page+1)*data.pageSize>=data.total;
    }catch(e){if(sequence!==previewSequence)return;$('preview-state').textContent=e.message;$('previous-page').disabled=true;$('next-page').disabled=true;}
  }
  function snippets(container,docs){container.replaceChildren();if(!docs.length){container.append(el('p','暂无匹配片段。','empty'));return;}
    docs.forEach((doc,index)=>{const article=el('article',undefined,'snippet');article.append(el('h3',doc.title||doc.source||'片段 '+(index+1)),el('pre',doc.content||''));if(doc.truncated)article.append(el('small','预览已截断，完整文本仍保存在知识库。'));container.append(article);});}
  $('close-preview').addEventListener('click',()=>$('preview').close());
  $('previous-page').addEventListener('click',()=>openPreview(preview.id,preview.page-1));$('next-page').addEventListener('click',()=>openPreview(preview.id,preview.page+1));
  $('search-form').addEventListener('submit',async event=>{event.preventDefault();$('search').disabled=true;$('search-state').textContent='正在检索…';$('search-results').replaceChildren();
    try{const result=await send('/search-test','POST',{knowledgeTag:$('search-tag').value,query:$('search-query').value.trim()});
      const outcomes={success:'成功',failed:'失败',timeout:'超时',rejected:'繁忙',unsupported_filter:'过滤条件不支持'};
      $('search-state').textContent=(result.degraded?'部分检索分支不可用，已使用可用结果。':'检索完成。')+' '+result.latencyMs+' ms · '+Object.entries(result.branches).map(([k,v])=>(k==='vector'?'向量':'关键词')+'：'+(outcomes[v]||v)).join(' · ');
      snippets($('search-results'),result.documents);
    }catch(e){$('search-state').textContent=e.message;}finally{$('search').disabled=false;}
  });
  request('/api/v1/auth/me').then(user=>{$('account').textContent=user.username||'';}).catch(()=>{});load();
})();
