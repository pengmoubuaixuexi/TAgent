(() => {
    'use strict';
    const $=id=>document.getElementById(id),path='/api/v1/improvements',encode=encodeURIComponent;
    const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;if(cls)e.className=cls;return e;};
    const statusText={ANALYZING:'分析中',FAILED:'分析失败',EXTERNAL:'外部故障',NO_CHANGE:'无需自动修改',PENDING_VALIDATION:'待验证',VALIDATING:'验证中',
        VALIDATION_FAILED:'验证失败',VALIDATED:'已验证',ADOPTED:'已采纳',REJECTED:'已拒绝'};
    const statusTone={ANALYZING:'wait',VALIDATING:'wait',FAILED:'bad',VALIDATION_FAILED:'bad',EXTERNAL:'bad',VALIDATED:'good',ADOPTED:'good'};
    const categories={PROMPT_UNCLEAR:'提示词不清',WRONG_TOOL:'选错工具或参数',MISSING_TOOL:'缺少需要的工具',RETRIEVAL_MISS:'检索没有命中',
        MODEL_LIMIT:'模型能力不足',EXTERNAL_FAILURE:'外部故障',NONE:'未发现明显问题'};
    const fields={systemPrompt:'节点提示词',outputRequirement:'输出要求',taskPrompt:'任务指令'};
    const adviceTypes={TOOL_BINDING:'工具绑定',PARAMETER:'参数',KNOWLEDGE:'知识库',MODEL:'模型',OTHER:'其他'};
    let timer=null,busy=false;
    async function api(url,method='GET') {
        const response=await fetch(url,{method,cache:'no-store',headers:{Accept:'application/json'}});
        let payload;try{payload=await response.json();}catch(_){throw new Error('无法读取响应（HTTP '+response.status+'）');}
        if(!response.ok||payload.code!=='0000')throw new Error(payload.info||'请求失败');return payload.data;
    }
    function notice(message,error=false){$('notice').textContent=message||'';$('notice').className=error?'error':'';}
    const badge=status=>el('span',statusText[status]||status,'status '+(statusTone[status]||''));
    const time=value=>value?new Date(value.replace(' ','T')).toLocaleString('zh-CN'):'';
    async function list() {
        $('list-section').hidden=false;$('detail').hidden=true;
        try{const rows=await api(path);const box=$('list');box.replaceChildren();
            if(!rows.length)box.append(el('p','还没有改进建议。在运行历史或产物与反馈页对一次运行点「分析并提出改进」即可发起。','muted'));
            const wrap=el('div',null,'suggestions');
            for(const s of rows){const a=el('a',null,'suggestion-link');a.href='/improvements.html?id='+encode(s.suggestionId);
                const title=el('strong',(s.agentName||s.agentId||'Agent')+' · '+(s.diagnosis?.summary||'运行 '+String(s.runId).slice(0,8)));title.append(badge(s.status));
                a.append(title,el('span',(categories[s.diagnosis?.category]||'')+(s.diagnosis?.category?' · ':'')+time(s.createdAt),'muted'));wrap.append(a);}
            box.append(wrap);
        }catch(e){notice(e.message,true);}
    }
    async function show(id) {
        clearTimeout(timer);$('list-section').hidden=true;$('detail').hidden=false;
        try{const s=await api(path+'/'+encode(id));render(s);
            if(s.status==='ANALYZING')timer=setTimeout(()=>show(id),5000);
            if(s.status==='VALIDATING')timer=setTimeout(()=>show(id),10000);
        }catch(e){notice(e.message,true);}
    }
    function render(s) {
        const box=$('detail');box.replaceChildren();
        const head=el('div',null,'row'),title=el('h2',(s.agentName||s.agentId||'Agent'));title.append(badge(s.status));head.append(title);
        const back=el('a','全部建议','button');back.href='/improvements.html';head.append(back);box.append(head);
        const source=el('p',null,'muted'),run=el('a','运行 '+String(s.runId).slice(0,8));run.href='/history.html?runId='+encode(s.runId);
        source.append('来源：',run,(s.agentVersion?' · 当时版本 v'+s.agentVersion:'')+(s.sourceKind==='EVAL_CASE'?' · 来自评测失败题':'')+' · 发起于 '+time(s.createdAt)
            +(s.reviewModel?' · 复盘模型 '+s.reviewModel+(s.reviewTokens?'，约 '+s.reviewTokens+' Token':''):''));box.append(source);
        if(s.status==='ANALYZING')box.append(el('p','正在读取这次运行的完整轨迹并分析，通常需要 1 到 3 分钟，页面会自动刷新。','muted'));
        if(s.error)box.append(el('p',s.error,'error'));
        const d=s.diagnosis||{};
        if(d.summary||d.category){
            box.append(el('h3','诊断'),el('p',d.summary||''));const dl=el('dl');
            for(const [k,v] of [['原因类别',categories[d.category]||d.category],['出问题的节点',d.problemNodeName?d.problemNodeName+'（'+d.problemNode+'）':null],['外部故障',d.externalReason]])
                if(v){dl.append(el('dt',k),el('dd',v));}
            box.append(dl);
            if((d.evidence||[]).length){const ul=el('ul',null,'evidence');for(const item of d.evidence){const li=el('li');
                // seq 0: taken from the node configuration, which has no trace record to open.
                if(Number(item.seq)===0){li.append(el('strong','节点配置'),' '+(item.observation||''));ul.append(li);continue;}
                const a=el('a','#'+item.seq);a.href='/history.html?runId='+encode(s.runId)+'&seq='+item.seq;li.append(a,' '+(item.observation||''));ul.append(li);}box.append(el('h3','证据（点击查看轨迹记录）'),ul);}
        }
        const sug=s.suggestion||{};
        if(s.agentVersion&&s.currentVersion&&Number(s.agentVersion)!==Number(s.currentVersion)&&['PENDING_VALIDATION','VALIDATION_FAILED'].includes(s.status))
            box.append(el('p',`这次运行用的是 v${s.agentVersion}，Agent 现在是 v${s.currentVersion}。只有建议涉及的提示词仍与“修改前”一致时才能生成草稿；发生冲突请基于当前版本重新运行并复盘。`,'error'));
        if(sug.notEditable)box.append(el('p',sug.notEditable,'muted'));
        if((sug.promptChanges||[]).length){box.append(el('h3','修改建议'));
            for(const c of sug.promptChanges){const card=el('div',null,'change');card.append(el('h3',(c.nodeName||c.nodeId)+' · '+(fields[c.field]||c.field)));
                if(c.reason)card.append(el('p',c.reason,'muted'));card.append(diffView(c.before||'',c.after||''));box.append(card);}}
        if((sug.otherAdvice||[]).length){box.append(el('h3','需要你在编辑器中处理的建议'));const ul=el('ul');
            for(const a of sug.otherAdvice)ul.append(el('li','【'+(adviceTypes[a.type]||a.type)+'】'+a.advice));box.append(ul);}
        const v=s.validation||{};
        if(v.datasetId){box.append(el('h3','对比验证'),el('p',`验证题目 ${v.questions} 道：出问题的原问题，加上这个 Agent 评测过的题目。当前版本和草稿各跑一遍。`,'muted'));
            if(v.skipped)box.append(el('p',v.skipped,'error'));
            const links=el('p',null,'muted'),evalLink=el('a','在我的评测中查看');evalLink.href='/eval.html';links.append(evalLink);box.append(links);
            if(v.comparison)box.append(compareTable(v.comparison));else if(s.status==='VALIDATING')box.append(el('p','两个版本正在运行验证题目，完成后这里显示对比结果，页面会自动刷新。','muted'));}
        const actions=el('div',null,'actions');
        const button=(text,cls,handler)=>{const b=el('button',text,cls);b.type='button';b.onclick=handler;actions.append(b);};
        if(['PENDING_VALIDATION','VALIDATION_FAILED'].includes(s.status))button('生成草稿并对比验证','primary',()=>act(s,'validate'));
        if(s.status==='VALIDATED')button('启用草稿为新版本','primary',()=>act(s,'adopt','启用后这个 Agent 立即使用新版本；旧版本保留在版本历史中，可以随时恢复。确定启用吗？'));
        if(['PENDING_VALIDATION','VALIDATION_FAILED','VALIDATING','VALIDATED','NO_CHANGE','EXTERNAL','FAILED'].includes(s.status))
            button(s.status==='VALIDATING'?'放弃（停止验证并删除草稿）':['NO_CHANGE','EXTERNAL','FAILED'].includes(s.status)?'关闭这条建议':'放弃这条建议','',()=>act(s,'reject','放弃后会删除这条建议生成的草稿（如果编辑器中没有再改过）。确定放弃吗？'));
        if(s.status==='ADOPTED')box.append(el('p','已启用为新版本。之后的运行都会使用修改后的配置。','muted'));
        if(s.status==='REJECTED')box.append(el('p','已放弃，Agent 配置没有任何变化。','muted'));
        if(actions.children.length)box.append(actions);
    }
    async function act(s,action,question) {
        if(busy||(question&&!confirm(question)))return;busy=true;notice(action==='validate'?'正在生成草稿并启动验证…':'处理中…');
        try{const next=await api(path+'/'+encode(s.suggestionId)+'/'+action,'POST');notice(action==='validate'?'验证已开始。':action==='adopt'?'已启用新版本。':'已放弃。');render(next);
            if(next.status==='VALIDATING'){clearTimeout(timer);timer=setTimeout(()=>show(s.suggestionId),10000);}
        }catch(e){notice(e.message,true);}finally{busy=false;}
    }
    function compareTable(c) {
        const wrap=el('div',null,'table-wrap'),table=el('table',null,'compare'),b=c.baseline||{},a=c.candidate||{};
        const head=el('tr');['指标','当前版本','草稿'].forEach(t=>head.append(el('th',t)));table.append(head);
        const rows=[['完成率','passRate','%',1],['平均规则得分','averageScore','',1],['平均耗时','averageLatencyMs',' ms',-1],['Token 消耗','tokens','',-1]];
        for(const [label,key,unit,better] of rows){const tr=el('tr');tr.append(el('td',label),el('td',(b[key]??'—')+unit));
            const cell=el('td',(a[key]??'—')+unit);if(b[key]!=null&&a[key]!=null&&a[key]!==b[key])cell.className=(a[key]-b[key])*better>0?'better':'worse';tr.append(cell);table.append(tr);}
        wrap.append(table);
        const detail=el('details');detail.append(el('summary','逐题结果'));const t=el('table');const h=el('tr');['题目','当前版本','草稿'].forEach(x=>h.append(el('th',x)));t.append(h);
        const view=r=>!r||r.status==='MISSING'?'—':(r.status==='PASS'?'完成':r.status)+(r.score!=null?' · '+r.score+' 分':'')+(r.latencyMs!=null?' · '+(r.latencyMs/1000).toFixed(1)+' 秒':'');
        for(const line of c.cases||[]){const tr=el('tr');tr.append(el('td',line.question),el('td',view(line.baseline)),el('td',view(line.candidate)));t.append(tr);
            if(line.baseline?.answer||line.candidate?.answer){const answers=el('tr'),cell=el('td');cell.colSpan=3;
                for(const [label,r] of [['当前版本的回答',line.baseline],['草稿的回答',line.candidate]])if(r?.answer){cell.append(el('p',label,'muted'),el('pre',r.answer));
                    if(r.runId){const a=el('a','查看这次运行的轨迹');a.href='/history.html?runId='+encode(r.runId);cell.append(a);}}
                answers.append(cell);t.append(answers);}}
        detail.open=true;detail.append(t);wrap.append(detail);
        wrap.append(el('p','完成率指运行正常结束的比例；得分为规则评分（0–100），看不出答案是查到的还是猜的，请对照逐题回答和轨迹判断。','muted'));
        return wrap;
    }
    // Line diff: common prefix/suffix trimmed, LCS on the rest.
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
    function diffView(beforeText,afterText) {
        const before=beforeText.split(/\r?\n/),after=afterText.split(/\r?\n/),ops=diff(before,after),body=el('div',null,'diff-body');
        if(!ops){body.append(el('pre',afterText));return body;}
        for(const o of ops){const line=el('div',null,'diff-line'+(o[0]==='+'?' diff-add':o[0]==='-'?' diff-del':''));
            const text=o[0]==='+'?after[o[2]]:before[o[1]];
            [o[1]>=0?o[1]+1:'',o[2]>=0?o[2]+1:'',o[0]==='='?' ':o[0],text].forEach(v=>line.append(el('span',String(v))));body.append(line);}
        return body;
    }
    $('reload-list').onclick=list;
    const id=new URLSearchParams(location.search).get('id');
    if(id)show(id);else list();
})();
