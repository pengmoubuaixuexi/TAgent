(() => {
  'use strict';
  const endpoint='/api/v1/workspace/outcomes/artifacts', encode=encodeURIComponent;
  const el=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=String(text);return n;};
  async function api(url){const r=await fetch(url,{cache:'no-store',headers:{Accept:'application/json'}});const b=await r.json();if(!r.ok||b.code!=='0000')throw new Error('产物暂时无法读取，请重试');return b.data;}
  const sizeText=n=>{n=Number(n);if(!Number.isFinite(n)||n<=0)return'';return n<1024?n+' B':n<1048576?(n/1024).toFixed(1)+' KB':(n/1048576).toFixed(1)+' MB';};
  function fileKind(a){
    const mime=String(a.mimeType||a.content?.mimeType||''),name=String(a.filename||a.content?.filename||'').toLowerCase();
    if(mime.startsWith('image/'))return '图片';
    if(/\.(csv|tsv|xlsx|xls)$/.test(name))return '表格文件';
    if(/\.docx?$/.test(name))return 'Word 文档';
    if(name.endsWith('.pdf'))return 'PDF';
    return '文件';
  }
  /** FILE artifacts: the server returns an image link, the first rows of a table, or extracted text. */
  function filePreview(container,p){
    container.replaceChildren();
    if(p.kind==='image'){const img=el('img');img.src=p.url;img.alt=p.filename||'图片';img.className='run-artifact-image';container.append(img);return;}
    if(p.kind==='table'){
      for(const sheet of p.sheets||[]){
        if((p.sheets||[]).length>1)container.append(el('p',sheet.name||'表格'));
        const table=el('table'),head=el('tr');(sheet.columns||[]).forEach(c=>head.append(el('th',c)));table.append(head);
        (sheet.rows||[]).forEach(row=>{const tr=el('tr');row.forEach(v=>tr.append(el('td',v)));table.append(tr);});
        container.append(table);
      }
      container.append(el('p',(p.summary||'')+(p.truncated?' · 只预览前面一部分，下载可查看完整内容':'')));return;
    }
    if(p.kind==='text'){const pre=el('pre',p.text||'');container.append(pre);if(p.truncated)container.append(el('p','只预览前面一部分，下载可查看完整内容。'));return;}
    container.append(el('p',p.summary||'这种文件不能在页面里预览，请下载查看。'));
  }
  function preview(container,a){
    container.replaceChildren();
    if(a.format==='CODE'){
      const heading=el('p',[a.content?.filename||'代码文件',a.content?.language].filter(Boolean).join(' · '));
      heading.className='run-artifact-source-name';
      const source=el('pre'),code=el('code',a.content?.code||'');source.className='run-artifact-source';source.append(code);
      container.append(heading,source);return;
    }
    if(a.format==='TABLE'){
      const table=el('table'),head=el('tr');(a.content?.columns||[]).forEach(c=>head.append(el('th',c)));table.append(head);
      (a.content?.rows||[]).slice(0,100).forEach(row=>{const tr=el('tr');row.forEach(v=>tr.append(el('td',v)));table.append(tr);});
      container.append(table);if(a.content?.rows?.length>100)container.append(el('p','预览前 100 行，下载包含全部数据。'));return;
    }
    const source=a.content?.markdown||'';
    if(!window.marked){container.append(el('pre',source));return;}
    // Saved content is untrusted. Rebuild presentation-only elements and omit executable HTML.
    const template=document.createElement('template');template.innerHTML=window.marked.parse(source);
    const allowed=new Set(['P','BR','H1','H2','H3','H4','H5','H6','STRONG','EM','DEL','UL','OL','LI','BLOCKQUOTE','PRE','CODE','TABLE','THEAD','TBODY','TR','TH','TD','HR','A']);
    function clean(n){
      if(n.nodeType===Node.TEXT_NODE)return document.createTextNode(n.textContent);
      if(n.nodeType!==Node.ELEMENT_NODE||['SCRIPT','STYLE','IFRAME','OBJECT','FORM','SVG','MATH'].includes(n.tagName))return document.createDocumentFragment();
      const safe=allowed.has(n.tagName)?el(n.tagName.toLowerCase()):document.createDocumentFragment();
      if(n.tagName==='A'){try{const u=new URL(n.getAttribute('href'),location.origin);if(['http:','https:'].includes(u.protocol)){safe.href=u.href;safe.target='_blank';safe.rel='noopener noreferrer';}}catch(_){}}
      n.childNodes.forEach(child=>safe.append(clean(child)));return safe;
    }
    template.content.childNodes.forEach(n=>container.append(clean(n)));
  }
  function render(message,a,{fromRefresh=false}={}){
    // A revision saved by this run of an artifact created earlier carries sourceRunId.
    if(!message||!a?.artifactId||String(a.sourceRunId??a.runId)!==message.dataset.runId)return;
    const revision=Number(a.revision??a.currentRevision??1);if(!Number.isSafeInteger(revision)||revision<1)return;
    const key=String(a.artifactId)+':'+revision;let box=message.querySelector('.run-artifacts');
    if(!box){box=el('div');box.className='run-artifacts';(message.querySelector('.msg-content')||message).append(box);}
    // One card per artifact: a later revision saved by this run (e.g. a redrawn chart) replaces the earlier card.
    const sameArtifact=[...box.children].filter(c=>c.dataset.artifactId===String(a.artifactId));
    if(sameArtifact.some(c=>Number(c.dataset.revision)>revision))return;
    sameArtifact.filter(c=>Number(c.dataset.revision)<revision).forEach(c=>c.remove());
    const existing=[...box.children].some(c=>c.dataset.artifactKey===key),status=message.querySelector('.run-artifact-progress');
    // Refresh may return an older saved file while another file failed. Only a live success,
    // or a newly recovered card during a pending save, confirms that progress has completed.
    if(!fromRefresh||(!existing&&status?.dataset.phase==='saving'))status?.remove();
    if(existing)return;
    const card=el('section');card.className='run-artifact-card';card.dataset.artifactKey=key;card.dataset.artifactId=String(a.artifactId);card.dataset.revision=String(revision);
    const filename=a.filename||a.content?.filename,language=a.language||a.content?.language;
    const base=Number(a.baseRevision);
    const version=' · v'+revision+(Number.isSafeInteger(base)&&base>0?'（基于 v'+base+' 修改）':'');
    const kind=a.format==='CODE'?['代码',filename,language].filter(Boolean).join(' · '):a.format==='TABLE'?'表格 · CSV'
      :a.format==='FILE'?[fileKind(a),sizeText(a.size)].filter(Boolean).join(' · '):'文档 · Markdown';
    card.append(el('h4',a.title||'生成的产物'),el('p',kind+version+' · 已保存'));
    const actions=el('div'),toggle=el('button','预览'),download=el('a',a.format==='CODE'||a.format==='FILE'?'下载 '+(filename||'文件'):a.format==='TABLE'?'下载 CSV':'下载 Markdown'),edit=el('a',a.format==='FILE'?'查看':'查看与编辑');
    toggle.type='button';toggle.setAttribute('aria-expanded','false');
    download.href=endpoint+'/'+encode(a.artifactId)+'/export?revision='+revision;
    edit.href='/outcomes.html?runId='+encode(a.runId)+'&artifactId='+encode(a.artifactId)+'&revision='+revision;
    let diff=null;
    if(Number.isSafeInteger(base)&&base>0&&a.format!=='FILE'){diff=el('a','查看改动');diff.href=edit.href+'&compare='+base;}
    const body=el('div');body.className='run-artifact-preview';body.hidden=true;
    toggle.onclick=async()=>{
      body.hidden=!body.hidden;toggle.setAttribute('aria-expanded',String(!body.hidden));toggle.textContent=body.hidden?'预览':'收起';
      if(body.hidden||body.dataset.loaded)return;body.textContent='正在读取产物…';
      try{
        if(a.format==='FILE'){filePreview(body,await api(endpoint+'/'+encode(a.artifactId)+'/preview?revision='+revision));body.dataset.loaded='true';return;}
        const saved=await api(endpoint+'/'+encode(a.artifactId)+'?revision='+revision);if(!message.isConnected)return;
        // The card's run either created the artifact or saved this revision of an attached one.
        const fromRun=String(saved.runId)===message.dataset.runId||String(saved.sourceRunId)===message.dataset.runId;
        if(!fromRun||saved.artifactId!==a.artifactId||Number(saved.revision)!==revision)
          throw new Error('产物或版本不匹配，请刷新后重试');
        preview(body,saved);body.dataset.loaded='true';if(saved.format==='CODE'&&saved.content?.filename)download.textContent='下载 '+saved.content.filename;
      }catch(e){body.textContent=e.message;}
    };
    actions.append(toggle,download,edit);if(diff)actions.append(diff);card.append(actions,body);box.append(card);
  }
  async function refresh(message){
    if(!message?.dataset.runId||message._artifactFetch)return;
    const runId=message.dataset.runId;message._artifactFetch=true;
    try{const rows=await api(endpoint+'?runId='+encode(runId));if(message.isConnected&&message.dataset.runId===runId&&Array.isArray(rows))rows.forEach(a=>render(message,a,{fromRefresh:true}));}
    catch(_){/* Live history may not yet exist; persisted events and subsequent restores also recover cards. */}
    finally{message._artifactFetch=false;}
  }
  function progress(message,data){
    if(!message||!message.dataset.runId||!data||(data.runId!=null&&String(data.runId)!==message.dataset.runId))return;
    const labels={generating:'正在生成产物…',repairing:'产物格式需要修复，正在修复…',saving:'正在保存产物…',failed:'产物未能保存，请查看本次运行的提示。'};
    if(!Object.prototype.hasOwnProperty.call(labels,data.phase))return;
    let status=message.querySelector('.run-artifact-progress');
    if(!status){status=el('div');status.className='run-artifact-progress';status.setAttribute('role','status');status.setAttribute('aria-live','polite');(message.querySelector('.msg-content')||message).append(status);}
    status.dataset.phase=data.phase;
    const attempt=Number(data.attempt),suffix=data.phase==='repairing'&&Number.isSafeInteger(attempt)&&attempt>0?'（第 '+attempt+' 次）':'';
    status.textContent=labels[data.phase]+suffix;
  }
  window.RunArtifacts={render,refresh,progress,filePreview};
})();
