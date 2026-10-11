(() => {
    'use strict';
    const $=id=>document.getElementById(id),path='/api/v1/workspace/outcomes',encode=encodeURIComponent;
    const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const state={runs:[],run:null,artifact:null,feedback:{},format:'MARKDOWN',busy:false,dirty:false,offset:0,hasMore:true,originalCode:'',editorCode:''};
    const editorFields=['artifact-title','artifact-markdown','table-columns','table-rows','code-filename','code-language','artifact-code'];
    async function api(url,method='GET',body) {
        const response=await fetch(url,{method,cache:'no-store',headers:{Accept:'application/json',...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
        let payload;try{payload=await response.json();}catch(_){throw new Error('无法读取响应（HTTP '+response.status+'）');}
        if(!response.ok||payload.code!=='0000')throw new Error(payload.info||'请求失败');return payload.data;
    }
    function notice(message,error=false){$('notice').textContent=message;$('notice').className=error?'error':'';}
    function ready() {
        document.querySelectorAll('button').forEach(b=>b.disabled=state.busy);
        ['new-markdown','new-table','new-code','save-feedback'].forEach(id=>$(id).disabled=state.busy||!state.run);
        $('more-runs').disabled=state.busy||!state.hasMore;$('run-select').disabled=state.busy;$('revision-select').disabled=state.busy;
        const historical=state.artifact&&state.artifact.revision!==state.artifact.currentRevision;
        editorFields.forEach(id=>$(id).disabled=state.busy||historical);
        $('save-artifact').disabled=state.busy||historical;$('history-note').hidden=!historical;
        $('copy-artifact').disabled=state.busy||state.dirty;
        $('export-artifact').hidden=!state.artifact||state.dirty;
        $('create-eval').disabled=state.busy||!!state.feedback.evalDatasetId;
    }
    async function work(fn){if(state.busy)return;state.busy=true;ready();try{await fn();}catch(e){notice(e.message,true);}finally{state.busy=false;ready();}}
    function discard(){return !state.dirty||confirm('产物有未保存的修改，是否放弃？');}
    async function loadRuns(reset=false) {
        if(reset){state.runs=[];state.offset=0;state.hasMore=true;}
        const rows=await api('/api/v1/history?offset='+state.offset+'&limit=30');
        state.runs.push(...rows);state.offset+=rows.length;state.hasMore=rows.length===30;
        $('run-select').innerHTML='<option value="">选择我的运行</option>'+state.runs.map(r=>`<option value="${esc(r.runId)}">${esc((r.question||'无问题').slice(0,70))} · ${esc(r.createdAt||'')}</option>`).join('');
        $('run-select').value=state.run?.runId||'';
    }
    async function openRun(id) {
        state.run=id?await api('/api/v1/history/'+encode(id)):null;state.artifact=null;state.dirty=false;
        $('artifact-form').hidden=true;$('run-answer').textContent=state.run?.answer||'尚无完整回答';
        $('run-detail').textContent=state.run?'Agent 版本 '+(state.run.agentVersion??'未知')+' · '+state.run.status+' · '+(state.run.updatedAt||''):'';
        state.feedback=id?await api(path+'/runs/'+encode(id)+'/feedback'):{};
        $('rating').value=state.feedback.rating||'HELPFUL';$('feedback-comment').value=state.feedback.comment||'';$('corrected-answer').value=state.feedback.correctedAnswer||'';
        $('create-eval').checked=false;$('eval-link').hidden=!state.feedback.evalDatasetId;
        $('feedback-saved').textContent=state.feedback.evalDatasetId?'已单独生成个人评测草稿。之后修改反馈不会改动那份草稿，请到评测页继续编辑。':state.feedback.revision?'反馈已保存 · v'+state.feedback.revision:'';
        await listArtifacts();
    }
    async function listArtifacts() {
        const rows=state.run?await api(path+'/artifacts?runId='+encode(state.run.runId)):[];
        $('artifact-list').innerHTML=rows.length?rows.map(a=>`<button data-artifact="${esc(a.artifactId)}" class="${a.artifactId===state.artifact?.artifactId?'selected':''}">${esc(a.title)} · ${a.format==='CODE'?'代码':a.format==='TABLE'?'表格':'文档'} v${esc(a.currentRevision)}</button>`).join(''):'<p class="muted">此运行还没有保存产物。</p>';
    }
    const writeTsv=rows=>rows.map(row=>row.map(v=>/[\t\r\n"]/.test(v)?'"'+v.replace(/"/g,'""')+'"':v).join('\t')).join('\n');
    function readTsv(text) {
        if(!text)return [];const rows=[];let row=[],cell='',quoted=false,closed=false;
        for(let i=0;i<text.length;i++){
            const ch=text[i];
            if(quoted){if(ch==='"'&&text[i+1]==='"'){cell+='"';i++;}else if(ch==='"'){quoted=false;closed=true;}else cell+=ch;continue;}
            if(ch==='"'&&!cell&&!closed){quoted=true;continue;}
            if(ch==='\t'||ch==='\n'||ch==='\r'){row.push(cell);cell='';closed=false;if(ch!=='\t'){rows.push(row);row=[];if(ch==='\r'&&text[i+1]==='\n')i++;}continue;}
            if(closed)throw new Error('引号包围的单元格后只能是分隔符');cell+=ch;
        }
        if(quoted)throw new Error('表格有未闭合的双引号');if(cell||row.length||closed||!/[\n\r]$/.test(text)){row.push(cell);rows.push(row);}return rows;
    }
    function content() {
        if(state.format==='MARKDOWN')return {markdown:$('artifact-markdown').value};
        if(state.format==='CODE'){
            // Textareas normalize line endings. Keep the original bytes when only metadata changes,
            // and retain an existing CRLF file's line-ending convention when its source is edited.
            const value=$('artifact-code').value,crlf=state.originalCode.includes('\r\n')&&!/(^|[^\r])\n|\r(?!\n)/.test(state.originalCode);
            const code=value===state.editorCode?state.originalCode:crlf?value.replace(/\n/g,'\r\n'):value;
            return {filename:$('code-filename').value,language:$('code-language').value,code};
        }
        const headers=readTsv($('table-columns').value);if(headers.length!==1)throw new Error('表头应为一行');
        return {columns:headers[0],rows:readTsv($('table-rows').value)};
    }
    function preview() {
        if(state.format!=='TABLE')return;let data;try{data=content();}catch(e){$('table-preview').textContent=e.message;return;}
        $('table-preview').innerHTML='<table><thead><tr>'+data.columns.map(c=>'<th>'+esc(c)+'</th>').join('')+'</tr></thead><tbody>'+data.rows.slice(0,30).map(row=>'<tr>'+row.map(c=>'<td>'+esc(c)+'</td>').join('')+'</tr>').join('')+'</tbody></table>'+(data.rows.length>30?'<p class="muted">预览前 30 行，保存和导出包含所有行。</p>':'');
    }
    function renderArtifact(a) {
        state.artifact=a;state.format=a.format;state.dirty=false;$('artifact-form').hidden=false;
        $('artifact-title').value=a.title;$('artifact-markdown').value=a.content?.markdown||'';
        $('code-filename').value=a.content?.filename||'';$('code-language').value=a.content?.language||'plaintext';
        $('code-filename').required=a.format==='CODE';$('code-language').required=a.format==='CODE';
        state.originalCode=a.content?.code||'';$('artifact-code').value=state.originalCode;state.editorCode=$('artifact-code').value;
        $('table-columns').value=writeTsv([a.content?.columns||['名称','说明']]);$('table-rows').value=writeTsv(a.content?.rows||[]);
        $('markdown-editor').hidden=a.format!=='MARKDOWN';$('table-editor').hidden=a.format!=='TABLE';$('code-editor').hidden=a.format!=='CODE';
        $('artifact-origin').textContent='来源 Agent v'+(a.agentVersion??state.run?.agentVersion??'未知')+(a.artifactId?' · 产物 v'+a.revision:' · 尚未保存');
        $('copy-artifact').hidden=!a.artifactId;$('copy-fallback').hidden=true;
        $('export-artifact').textContent=a.format==='CODE'?'下载 '+(a.content?.filename||'代码文件'):a.format==='TABLE'?'下载 CSV':'下载 Markdown';
        $('export-artifact').href=a.artifactId?path+'/artifacts/'+encode(a.artifactId)+'/export?revision='+a.revision:'#';
        preview();
    }
    async function openArtifact(id,revision=0) {
        const a=await api(path+'/artifacts/'+encode(id)+'?revision='+revision);renderArtifact(a);
        const versions=await api(path+'/artifacts/'+encode(id)+'/revisions');
        $('revision-select').innerHTML=versions.map(v=>`<option value="${esc(v.revision)}">v${esc(v.revision)} · ${esc(v.createdAt)}</option>`).join('');
        $('revision-select').value=String(a.revision);await listArtifacts();
    }
    function create(format) {
        if(!state.run||!discard())return;
        renderArtifact({title:(state.run.question||'新产物').slice(0,60),format,content:format==='MARKDOWN'?{markdown:state.run.answer||''}:format==='CODE'?{filename:'example.py',language:'python',code:''}:{columns:['名称','说明'],rows:[]}});
        state.artifact=null;state.dirty=true;$('revision-select').innerHTML='<option>尚未保存</option>';ready();
    }
    $('run-select').onchange=()=>{if(!discard()){$('run-select').value=state.run?.runId||'';return;}work(()=>openRun($('run-select').value));};
    $('more-runs').onclick=()=>work(()=>loadRuns());$('reload').onclick=()=>{if(discard())work(async()=>{await loadRuns(true);if(state.run)await openRun(state.run.runId);});};
    $('new-markdown').onclick=()=>create('MARKDOWN');$('new-table').onclick=()=>create('TABLE');$('new-code').onclick=()=>create('CODE');
    $('artifact-list').onclick=e=>{const b=e.target.closest('[data-artifact]');if(b&&discard())work(()=>openArtifact(b.dataset.artifact));};
    $('revision-select').onchange=()=>{if(discard())work(()=>openArtifact(state.artifact.artifactId,Number($('revision-select').value)));else $('revision-select').value=state.artifact?.revision;};
    editorFields.forEach(id=>$(id).oninput=()=>{state.dirty=true;preview();ready();});
    $('artifact-form').onsubmit=e=>{e.preventDefault();work(async()=>{
        const previous=state.artifact,body={title:$('artifact-title').value,format:state.format,content:content(),...(previous?{revision:previous.revision}:{runId:state.run.runId})};
        const saved=await api(path+'/artifacts'+(previous?'/'+encode(previous.artifactId):''),previous?'PUT':'POST',body);
        await openArtifact(saved.artifactId);notice('产物 v'+saved.revision+' 已保存，历次版本保持不变。');
    });};
    $('copy-artifact').onclick=()=>work(async()=>{
        const a=state.artifact;if(!a||state.dirty)throw new Error('请先保存产物');
        const text='我明确引用以下已保存产物作为本次输入：\n产物：'+a.title+'\n来源：artifactId='+a.artifactId+'，revision='+a.revision+'，runId='+a.runId+'\n\n'+(a.format==='CODE'?'文件：'+a.content.filename+(a.content.language?'（'+a.content.language+'）':'')+'\n'+a.content.code:a.format==='MARKDOWN'?a.content.markdown:[a.content.columns,...a.content.rows].map(row=>row.join('\t')).join('\n'));
        try{await navigator.clipboard.writeText(text);notice('已复制。请粘贴到对话输入框，检查后发送。');}catch(_){$('copy-fallback').hidden=false;$('copy-fallback').value=text;$('copy-fallback').focus();$('copy-fallback').select();notice('请复制下方已选中的引用消息，再粘贴到对话中。');}
    });
    $('feedback-form').onsubmit=e=>{e.preventDefault();work(async()=>{
        if(!state.run)throw new Error('请先选择来源运行');
        const saved=await api(path+'/runs/'+encode(state.run.runId)+'/feedback','PUT',{revision:state.feedback.revision||0,rating:$('rating').value,comment:$('feedback-comment').value,correctedAnswer:$('corrected-answer').value,createEvalDraft:$('create-eval').checked});
        state.feedback=saved;$('create-eval').checked=false;$('eval-link').hidden=!saved.evalDatasetId;
        $('feedback-saved').textContent=saved.evalDatasetId?'已生成个人评测草稿，题目未启用。后续修改反馈不会覆盖评测草稿。':'反馈已保存 · v'+saved.revision;
        notice('反馈已保存'+(saved.evalDatasetId?'，可前往我的评测审阅草稿。':'。'));
    });};
    window.addEventListener('beforeunload',e=>{if(state.dirty){e.preventDefault();e.returnValue='';}});
    work(async()=>{
        await loadRuns(true);const query=new URLSearchParams(location.search),runId=query.get('runId');
        if(runId){
            await openRun(runId);if(!state.runs.some(r=>r.runId===runId))$('run-select').add(new Option((state.run.question||'当前运行').slice(0,70),runId));
            $('run-select').value=runId;
            const artifactId=query.get('artifactId'),revision=Number(query.get('revision')||0);
            if(artifactId&&Number.isSafeInteger(revision)&&revision>=0){
                const a=await api(path+'/artifacts/'+encode(artifactId)+'?revision='+revision);
                if(a.runId!==runId)throw new Error('该产物不属于选中的运行');
                await openArtifact(artifactId,revision);
            }
        }
    });
})();
