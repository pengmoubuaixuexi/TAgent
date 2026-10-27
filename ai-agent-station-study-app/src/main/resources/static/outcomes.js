(() => {
    'use strict';
    const $=id=>document.getElementById(id),path='/api/v1/workspace/outcomes',encode=encodeURIComponent;
    const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const state={runs:[],run:null,artifact:null,feedback:{},format:'MARKDOWN',busy:false,dirty:false,offset:0,hasMore:true,originalCode:'',editorCode:'',artifacts:[],artifactsMore:false};
    // Server times are UTC instants ("…Z") or local "yyyy-MM-dd HH:mm:ss"; both are shown in the viewer's time zone.
    function when(value) {
        if(value==null||value==='')return '';
        const text=String(value),date=new Date(/[zZ]$|[+-]\d\d:?\d\d$/.test(text)?text:text.replace(' ','T'));
        if(isNaN(date))return text;
        const two=n=>String(n).padStart(2,'0');
        return (date.getFullYear()===new Date().getFullYear()?'':date.getFullYear()+'-')+two(date.getMonth()+1)+'-'+two(date.getDate())+' '+two(date.getHours())+':'+two(date.getMinutes());
    }
    const formatLabel=f=>f==='CODE'?'代码':f==='TABLE'?'表格':f==='FILE'?'文件':'文档';
    const editorFields=['artifact-title','artifact-markdown','table-columns','table-rows','code-filename','code-language','artifact-code'];
    async function api(url,method='GET',body) {
        const response=await fetch(url,{method,cache:'no-store',headers:{Accept:'application/json',...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
        let payload;try{payload=await response.json();}catch(_){throw new Error('无法读取响应（HTTP '+response.status+'）');}
        if(!response.ok||payload.code!=='0000')throw new Error(payload.info||'请求失败');return payload.data;
    }
    function notice(message,error=false){$('notice').textContent=message;$('notice').className=error?'error':'';}
    function ready() {
        document.querySelectorAll('button').forEach(b=>b.disabled=state.busy);
        ['new-markdown','new-table','new-code','save-feedback','improve'].forEach(id=>$(id).disabled=state.busy||!state.run);
        $('more-runs').disabled=state.busy||!state.hasMore;$('run-select').disabled=state.busy;$('revision-select').disabled=state.busy;
        $('artifact-scope').querySelector('[value="run"]').disabled=!state.run;$('more-artifacts').hidden=!state.artifactsMore;
        const historical=state.artifact&&state.artifact.revision!==state.artifact.currentRevision;
        editorFields.forEach(id=>$(id).disabled=state.busy||historical);
        $('save-artifact').disabled=state.busy||historical;$('history-note').hidden=!historical;
        $('copy-artifact').disabled=state.busy||state.dirty;$('compare-select').disabled=state.busy;$('run-code').disabled=state.busy||state.dirty;
        if(state.format==='FILE'){editorFields.forEach(id=>$(id).disabled=true);$('save-artifact').hidden=true;}else $('save-artifact').hidden=false;
        $('export-artifact').hidden=!state.artifact||state.dirty;
        $('create-eval').disabled=state.busy||!!state.feedback.evalDatasetId;
    }
    async function work(fn){if(state.busy)return;state.busy=true;ready();try{await fn();}catch(e){notice(e.message,true);}finally{state.busy=false;ready();}}
    function discard(){return !state.dirty||confirm('产物有未保存的修改，是否放弃？');}
    async function loadRuns(reset=false) {
        if(reset){state.runs=[];state.offset=0;state.hasMore=true;}
        const keyword=$('run-search').value.trim();
        const rows=await api('/api/v1/history?offset='+state.offset+'&limit=30'+(keyword?'&q='+encode(keyword):''));
        state.runs.push(...rows);state.offset+=rows.length;state.hasMore=rows.length===30;
        const label=r=>esc((r.question||'无问题').slice(0,70))+' · '+esc(when(r.createdAt));
        const options=state.runs.map(r=>`<option value="${esc(r.runId)}">${label(r)}</option>`);
        // The open run stays selectable while the search hides it.
        if(state.run&&!state.runs.some(r=>r.runId===state.run.runId))options.unshift(`<option value="${esc(state.run.runId)}">${label(state.run)}</option>`);
        $('run-select').innerHTML='<option value="">'+(keyword&&!state.runs.length?'没有匹配的运行':'选择我的运行')+'</option>'+options.join('');
        $('run-select').value=state.run?.runId||'';
    }
    async function selectRun(runId) {
        await openRun(runId);
        if(!$('run-select').querySelector(`option[value="${CSS.escape(runId)}"]`))
            $('run-select').add(new Option((state.run.question||'当前运行').slice(0,70)+' · '+when(state.run.createdAt),runId),1);
        $('run-select').value=runId;
    }
    async function openRun(id) {
        state.run=id?await api('/api/v1/history/'+encode(id)):null;state.artifact=null;state.dirty=false;
        $('artifact-form').hidden=true;$('run-answer').textContent=state.run?.answer||'尚无完整回答';
        $('run-detail').textContent=state.run?'Agent 版本 '+(state.run.agentVersion??'未知')+' · '+state.run.status+' · '+when(state.run.updatedAt):'';
        state.feedback=id?await api(path+'/runs/'+encode(id)+'/feedback'):{};
        $('rating').value=state.feedback.rating||'HELPFUL';$('feedback-comment').value=state.feedback.comment||'';$('corrected-answer').value=state.feedback.correctedAnswer||'';
        $('create-eval').checked=false;$('eval-link').hidden=!state.feedback.evalDatasetId;
        $('feedback-saved').textContent=state.feedback.evalDatasetId?'已单独生成个人评测草稿。之后修改反馈不会改动那份草稿，请到评测页继续编辑。':state.feedback.revision?'反馈已保存 · v'+state.feedback.revision:'';
        await refreshArtifacts();
    }
    // "全部产物" pages through every artifact of the user; "当前运行" lists the open run's ones.
    async function listArtifacts(more=false) {
        const all=!state.run||$('artifact-scope').value==='all',keyword=$('artifact-search').value.trim();
        if(!state.run)$('artifact-scope').value='all';
        if(all){
            const page=await api(path+'/artifacts?offset='+(more?state.artifacts.length:0)+(keyword?'&q='+encode(keyword):''));
            state.artifacts=more?state.artifacts.concat(page):page;state.artifactsMore=page.length===100;
        }else{
            const rows=await api(path+'/artifacts?runId='+encode(state.run.runId));
            state.artifacts=keyword?rows.filter(a=>String(a.title||'').toLowerCase().includes(keyword.toLowerCase())):rows;state.artifactsMore=false;
        }
        renderArtifactList(all,keyword);
    }
    // The full list keeps its loaded pages when only the selection changes; a save or another run's list reloads it.
    async function refreshArtifacts(id,saved=false) {
        const all=!state.run||$('artifact-scope').value==='all';
        if(!all||saved||!state.artifacts.length||(id&&!state.artifacts.some(a=>a.artifactId===id)))return listArtifacts();
        renderArtifactList(true,$('artifact-search').value.trim());
    }
    function renderArtifactList(all,keyword) {
        const list=$('artifact-list');list.classList.toggle('all',all);
        if(!state.artifacts.length){list.innerHTML='<p class="muted">'+(keyword?'没有名称匹配的产物。':all?'还没有保存过产物。':'此运行还没有保存产物。')+'</p>';return;}
        list.innerHTML=state.artifacts.map(a=>{
            const current=state.run&&a.runId===state.run.runId,selected=a.artifactId===state.artifact?.artifactId;
            const meta=all?`<small>${esc(when(a.updatedAt))}${a.runQuestion?' · 来源：'+esc(a.runQuestion):''}</small>`:'';
            return `<button type="button" data-artifact="${esc(a.artifactId)}" data-run="${esc(a.runId)}" class="${selected?'selected':''}">${esc(a.title)} · ${formatLabel(a.format)} v${esc(a.currentRevision)}${all&&current?'<span class="tag">当前运行</span>':''}${meta}</button>`;
        }).join('');
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
        $('file-view').hidden=a.format!=='FILE';$('run-result').hidden=true;
        if(a.format==='FILE'&&a.artifactId)loadFilePreview(a);
        const python=a.format==='CODE'&&(/^python/i.test(a.content?.language||'')||/\.py$/i.test(a.content?.filename||''));
        $('run-code').hidden=!(a.artifactId&&python);
        for(const as of ['docx','pdf']){const link=$('export-'+as);link.hidden=!(a.artifactId&&a.format==='MARKDOWN');link.href=a.artifactId?path+'/artifacts/'+encode(a.artifactId)+'/export?revision='+a.revision+'&as='+as:'#';}
        $('artifact-origin').textContent='来源 Agent v'+(a.agentVersion??state.run?.agentVersion??'未知')+(a.artifactId?' · 产物 v'+a.revision:' · 尚未保存');
        $('copy-artifact').hidden=!a.artifactId;
        $('export-artifact').textContent=a.format==='CODE'||a.format==='FILE'?'下载 '+(a.content?.filename||'文件'):a.format==='TABLE'?'下载 CSV':'下载 Markdown';
        $('export-artifact').href=a.artifactId?path+'/artifacts/'+encode(a.artifactId)+'/export?revision='+a.revision:'#';
        preview();
    }
    const source=v=>v.sourceKind==='AGENT'?(v.baseRevision?' · Agent 基于 v'+v.baseRevision+' 修改':' · Agent 生成'):v.sourceKind==='USER'?' · 手动编辑':'';
    async function loadFilePreview(a) {
        $('file-preview').textContent='正在生成预览…';
        try{const p=await api(path+'/artifacts/'+encode(a.artifactId)+'/preview?revision='+a.revision);if(state.artifact?.artifactId===a.artifactId)window.RunArtifacts.filePreview($('file-preview'),p);}
        catch(e){$('file-preview').textContent=e.message;}
    }
    const runLabels={OK:'运行成功',ERROR:'代码出错',TIMEOUT:'运行超时，已停止',OOM:'内存超限，已终止',KILLED:'进程被终止',BUSY:'沙箱繁忙，请稍后再试',REJECTED:'沙箱拒绝了这次运行'};
    function renderRunResult(r) {
        const box=$('run-result');box.hidden=false;box.replaceChildren();
        const head=document.createElement('p');head.className=r.status==='OK'?'':'error';
        head.textContent=(runLabels[r.status]||r.status)+(r.durationMs!=null?' · '+(r.durationMs/1000).toFixed(1)+' 秒':'')+(r.message&&r.status!=='ERROR'?' · '+r.message:'');box.append(head);
        for(const [label,text] of [['标准输出',r.stdout],['错误输出',r.stderr]]){if(!text)continue;const t=document.createElement('p');t.className='muted';t.textContent=label;const pre=document.createElement('pre');pre.textContent=text;box.append(t,pre);}
        if((r.outputs||[]).length){const t=document.createElement('p');t.className='muted';t.textContent='生成的文件（14 分钟内可下载，不会自动保存为产物）';box.append(t);
            for(const f of r.outputs){const link=document.createElement('a');link.className='button';link.textContent='下载 '+f.name;
                link.href=path+'/sandbox-runs/'+encode(r.executionId)+'/outputs/'+f.index+'?name='+encode(f.name.split('/').pop());box.append(link,document.createTextNode(' '));}}
        for(const f of r.skippedOutputs||[]){const p=document.createElement('p');p.className='muted';p.textContent='未收集：'+f.name+'（'+f.reason+'）';box.append(p);}
    }
    async function openArtifact(id,revision=0,compare=0,saved=false) {
        const a=await api(path+'/artifacts/'+encode(id)+'?revision='+revision);renderArtifact(a);
        const versions=await api(path+'/artifacts/'+encode(id)+'/revisions');state.versions=versions;
        $('revision-select').innerHTML=versions.map(v=>`<option value="${esc(v.revision)}">v${esc(v.revision)} · ${esc(when(v.createdAt))}${esc(source(v))}</option>`).join('');
        $('revision-select').value=String(a.revision);
        const viewed=versions.find(v=>v.revision===a.revision),older=versions.filter(v=>v.revision<a.revision);
        $('compare-select').innerHTML='<option value="">不对比</option>'+older.map(v=>`<option value="${esc(v.revision)}">与 v${esc(v.revision)} 对比</option>`).join('');
        const suggested=compare||(viewed&&viewed.baseRevision)||0;
        $('compare-select').value=compare&&older.some(v=>v.revision===compare)&&a.format!=='FILE'?String(compare):'';
        // Files (images, spreadsheets) have no line diff; their versions are browsed with the version selector.
        $('compare-select').closest('label').hidden=a.format==='FILE';
        $('diff-view').hidden=true;
        if($('compare-select').value)await showDiff(Number($('compare-select').value));
        else if(suggested&&!compare&&a.format!=='FILE')$('compare-select').title='可以选择 v'+suggested+' 查看本次改动';
        await refreshArtifacts(id,saved);
    }
    function units(a) {
        if(a.format==='TABLE')return [a.content?.columns||[],...(a.content?.rows||[])].map(row=>row.join('\t'));
        return String(a.format==='CODE'?a.content?.code||'':a.content?.markdown||'').split(/\r?\n/);
    }
    // Line diff: common prefix/suffix trimmed, LCS on the rest. Typical edits change a small middle part.
    function diff(before,after) {
        let start=0;while(start<before.length&&start<after.length&&before[start]===after[start])start++;
        let endB=before.length,endA=after.length;while(endB>start&&endA>start&&before[endB-1]===after[endA-1]){endB--;endA--;}
        const b=before.slice(start,endB),a=after.slice(start,endA),ops=[];
        for(let i=0;i<start;i++)ops.push(['=',i,i]);
        if(b.length*a.length>4_000_000)return null;
        const w=a.length+1,table=new Uint32Array((b.length+1)*w);
        for(let i=b.length-1;i>=0;i--)for(let j=a.length-1;j>=0;j--)
            table[i*w+j]=b[i]===a[j]?table[(i+1)*w+j+1]+1:Math.max(table[(i+1)*w+j],table[i*w+j+1]);
        let i=0,j=0;
        while(i<b.length||j<a.length){
            if(i<b.length&&j<a.length&&b[i]===a[j]){ops.push(['=',start+i,start+j]);i++;j++;}
            else if(i<b.length&&(j>=a.length||table[(i+1)*w+j]>=table[i*w+j+1])){ops.push(['-',start+i,-1]);i++;}
            else{ops.push(['+',-1,start+j]);j++;}
        }
        for(let k=0;k<before.length-endB;k++)ops.push(['=',endB+k,endA+k]);
        return ops;
    }
    async function showDiff(base) {
        const current=state.artifact;if(!current||!base){$('diff-view').hidden=true;return;}
        const old=await api(path+'/artifacts/'+encode(current.artifactId)+'?revision='+base);
        const before=units(old),after=units(current),ops=diff(before,after),body=$('diff-body');
        $('diff-title').textContent='v'+base+' → v'+current.revision+' 的改动';body.replaceChildren();$('diff-view').hidden=false;
        if(!ops){$('diff-stats').textContent='改动范围太大，无法逐行对比';return;}
        const added=ops.filter(o=>o[0]==='+').length,removed=ops.filter(o=>o[0]==='-').length;
        $('diff-stats').textContent=added||removed?'新增 '+added+' 行，删除 '+removed+' 行'+(old.format!==current.format?'（格式不同）':''):'两个版本内容相同';
        const keep=new Set();ops.forEach((o,k)=>{if(o[0]!=='=')for(let d=-3;d<=3;d++)keep.add(k+d);});
        let skipped=0;
        const flush=()=>{if(skipped){body.append(Object.assign(document.createElement('div'),{className:'diff-gap',textContent:'… '+skipped+' 行未改动 …'}));skipped=0;}};
        ops.forEach((o,k)=>{
            if(o[0]==='='&&!keep.has(k)){skipped++;return;}
            flush();
            const line=document.createElement('div');line.className='diff-line'+(o[0]==='+'?' diff-add':o[0]==='-'?' diff-del':'');
            const text=o[0]==='+'?after[o[2]]:before[o[1]];
            [o[1]>=0?o[1]+1:'',o[2]>=0?o[2]+1:'',o[0]==='='?' ':o[0],text].forEach(v=>{const c=document.createElement('span');c.textContent=String(v);line.append(c);});
            body.append(line);
        });
        flush();
    }
    function create(format) {
        if(!state.run||!discard())return;
        renderArtifact({title:(state.run.question||'新产物').slice(0,60),format,content:format==='MARKDOWN'?{markdown:state.run.answer||''}:format==='CODE'?{filename:'example.py',language:'python',code:''}:{columns:['名称','说明'],rows:[]}});
        state.artifact=null;state.dirty=true;$('revision-select').innerHTML='<option>尚未保存</option>';ready();
    }
    $('run-select').onchange=()=>{if(!discard()){$('run-select').value=state.run?.runId||'';return;}
        if($('run-select').value)$('artifact-scope').value='run';work(()=>openRun($('run-select').value));};
    $('more-runs').onclick=()=>work(()=>loadRuns());$('reload').onclick=()=>{if(discard())work(async()=>{await loadRuns(true);if(state.run)await openRun(state.run.runId);else await listArtifacts();});};
    // Typing waits for a pause, and for any running request, before searching.
    function debounce(fn){let timer;const run=()=>{if(state.busy){timer=setTimeout(run,300);return;}work(fn);};return ()=>{clearTimeout(timer);timer=setTimeout(run,300);};}
    $('run-search').oninput=debounce(()=>loadRuns(true));
    $('artifact-search').oninput=debounce(()=>listArtifacts());
    $('artifact-scope').onchange=()=>work(()=>listArtifacts());
    $('more-artifacts').onclick=()=>work(()=>listArtifacts(true));
    $('new-markdown').onclick=()=>create('MARKDOWN');$('new-table').onclick=()=>create('TABLE');$('new-code').onclick=()=>create('CODE');
    $('artifact-list').onclick=e=>{const b=e.target.closest('[data-artifact]');if(!b||!discard())return;
        work(async()=>{
            // From the full list, an artifact of another run first opens that run (its feedback and answer).
            if($('artifact-scope').value==='all'&&b.dataset.run&&b.dataset.run!==state.run?.runId)await selectRun(b.dataset.run);
            await openArtifact(b.dataset.artifact);
        });};
    $('revision-select').onchange=()=>{if(discard())work(()=>openArtifact(state.artifact.artifactId,Number($('revision-select').value)));else $('revision-select').value=state.artifact?.revision;};
    $('compare-select').onchange=()=>work(()=>showDiff(Number($('compare-select').value)||0));
    $('run-code').onclick=()=>work(async()=>{
        const a=state.artifact;if(!a||state.dirty)throw new Error('请先保存代码');
        $('run-result').hidden=false;$('run-result').textContent='运行中…（在隔离沙箱里执行，最长 60 秒）';
        try{renderRunResult(await api(path+'/artifacts/'+encode(a.artifactId)+'/run?revision='+a.revision,'POST'));}
        catch(e){$('run-result').hidden=true;throw e;}
    });
    // Conversion takes a few seconds and can fail; a fetch keeps the page in place and shows why.
    for(const as of ['docx','pdf'])$('export-'+as).onclick=e=>{e.preventDefault();const href=e.currentTarget.href;work(async()=>{
        notice('正在导出 '+(as==='docx'?'Word':'PDF')+'…');
        const response=await fetch(href,{cache:'no-store'});
        if(!response.ok){let info='导出失败（HTTP '+response.status+'）';try{info=(await response.json()).info||info;}catch(_){}throw new Error(info);}
        const encoded=(response.headers.get('Content-Disposition')||'').match(/filename\*=UTF-8''([^;]+)/);
        const url=URL.createObjectURL(await response.blob()),link=document.createElement('a');
        link.href=url;link.download=encoded?decodeURIComponent(encoded[1]):'document.'+as;document.body.append(link);link.click();link.remove();
        setTimeout(()=>URL.revokeObjectURL(url),60000);notice('已导出 '+link.download);
    });};
    editorFields.forEach(id=>$(id).oninput=()=>{state.dirty=true;preview();ready();});
    $('artifact-form').onsubmit=e=>{e.preventDefault();work(async()=>{
        const previous=state.artifact,body={title:$('artifact-title').value,format:state.format,content:content(),...(previous?{revision:previous.revision}:{runId:state.run.runId})};
        const saved=await api(path+'/artifacts'+(previous?'/'+encode(previous.artifactId):''),previous?'PUT':'POST',body);
        await openArtifact(saved.artifactId,0,0,true);notice('产物 v'+saved.revision+' 已保存，历次版本保持不变。');
    });};
    $('copy-artifact').onclick=()=>{
        const a=state.artifact;if(!a||state.dirty){notice('请先保存产物',true);return;}
        location.href='/index.html?attachArtifact='+encode(a.artifactId)+'&revision='+encode(a.revision);
    };
    $('improve').onclick=()=>work(async()=>{
        if(!state.run)throw new Error('请先选择来源运行');
        const created=await api('/api/v1/improvements','POST',{runId:state.run.runId});
        location.href='/improvements.html?id='+encode(created.suggestionId);
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
        await loadRuns(true);const query=new URLSearchParams(location.search);let runId=query.get('runId');
        if(runId)$('artifact-scope').value='run';
        // Links from chat attachments carry only the artifact; its run is read from the artifact itself.
        if(!runId&&query.get('artifactId'))runId=(await api(path+'/artifacts/'+encode(query.get('artifactId'))+'?revision=0')).runId;
        if(!runId)await listArtifacts();
        if(runId){
            await selectRun(runId);
            const artifactId=query.get('artifactId'),revision=Number(query.get('revision')||0);
            if(artifactId&&Number.isSafeInteger(revision)&&revision>=0){
                const a=await api(path+'/artifacts/'+encode(artifactId)+'?revision='+revision);
                if(a.runId!==runId)throw new Error('该产物不属于选中的运行');
                const compare=Number(query.get('compare')||0);
                await openArtifact(artifactId,revision,Number.isSafeInteger(compare)&&compare>0?compare:0);
            }
        }
    });
})();
