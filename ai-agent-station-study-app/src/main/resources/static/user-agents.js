/* Node builder. Server owns identity, execution protocols and capability authorization. */
(() => {
    'use strict';
    const $ = id => document.getElementById(id);
    const root = $('tagent-node-builder');
    const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const copy = value => JSON.parse(JSON.stringify(value));
    const yes = value => value === true || value === 1 || value === '1';
    const usableMcp = m => !!m && (m.usable === undefined ? yes(m.status) : yes(m.usable));
    const state = {options:null, agents:[], mcps:[], tools:{}, agent:null, selected:0, tab:'prompt', dirty:false,
        busy:false, draft:null, scenes:{}, mcp:null, mcpDirty:false, links:[]};
    const editable = () => state.agent && (!state.agent.agentId || yes(state.agent.editable));
    const node = () => state.agent?.nodes?.[state.selected];
    const executable = n => ['DEFAULT','PRECISION_EXECUTOR_CLIENT','EXECUTOR_CLIENT'].includes(n.role);
    const inheritsFlowTools = n => state.agent?.strategy==='flow'&&['TOOL_MCP_CLIENT','PLANNING_CLIENT'].includes(n?.role);
    const flowExecutorIndex = () => state.agent?.nodes?.findIndex(n=>n.role==='EXECUTOR_CLIENT')??-1;
    const connectionBinding = mcpId => ({mcpId,allTools:true,toolNames:[],loadMode:'ON_DEMAND'});
    const restrictedBinding = binding => !!binding&&!yes(binding.allTools);
    const endpoint = id => encodeURIComponent(id);
    function notify(message, kind='') { $('notice').textContent=message; $('notice').className='notice '+kind; }
    function dirty() { state.dirty=true; renderSaveState(); }
    function renderSaveState() {
        $('save-state').textContent=state.dirty?'未保存':state.agent?.agentId?'已保存 · v'+state.agent.version:'私人配置';
        $('save-state').className='ta-tag'+(state.dirty?' dirty-tag':'');
    }
    async function request(path, options={}) {
        const r=await fetch(path.startsWith('/api/')?path:'/api/v1/workspace'+path, {cache:'no-store',...options,
            headers:{Accept:'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...options.headers}});
        if(r.status===401) {location.replace('/index.html');throw new Error('请先登录');}
        let payload; try{payload=await r.json();}catch(_){throw new Error('请求失败（HTTP '+r.status+'）');}
        if(!r.ok || payload.code!=='0000') throw new Error((payload.info||'请求失败')+(r.status===409?'。当前输入已保留，请重新加载最新版本。':''));
        return payload.data;
    }
    const send = (path,method,body) => request(path,{method,...(body?{body:JSON.stringify(body)}:{})});
    function refreshEnabled() {
        const ready=!!state.options, blocked=state.busy||!ready;
        $('agent-fields').disabled=blocked||!editable();
        ['new-agent','reload','manage-mcps','agent-select'].forEach(id=>$(id).disabled=blocked);
        $('save-agent').disabled=blocked||!editable()||!state.options?.models?.length;
        $('archive-agent').disabled=blocked;
        $('generate-draft').disabled=blocked||!editable()||!state.options?.models?.length;
        $('draft-requirement').disabled=blocked||!editable(); $('draft-model').disabled=blocked||!editable();
        root.querySelectorAll('[data-use-public], [data-draft-action]').forEach(b=>b.disabled=blocked);
        const mcpEditable=!state.mcp||yes(state.mcp.editable);
        $('mcp-fields').disabled=blocked||!mcpEditable;
        $('save-mcp').disabled=blocked||!mcpEditable||(!state.mcp&&!state.options?.mcpAllowedHosts?.length);
        ['new-mcp','close-mcp','remove-mcp'].forEach(id=>$(id).disabled=blocked);
        $('new-mcp').disabled=blocked||!state.options?.mcpAllowedHosts?.length;
        $('discover-managed-mcp').disabled=blocked||!usableMcp(state.mcp);
        $('select-managed-tools').disabled=blocked||!usableMcp(state.mcp)||!editable()||!executable(node())||!!node().mcpBindings.find(b=>b.mcpId===state.mcp?.mcpId);
        $('mcp-connections').querySelectorAll('button').forEach(b=>b.disabled=blocked);
    }
    async function work(fn) {
        if(state.busy)return;
        state.busy=true;refreshEnabled();
        try{await fn();}catch(e){notify(e.message,'error');}
        finally{state.busy=false;refreshEnabled();}
    }
    function discard() {return !state.dirty||confirm('有尚未保存的 Agent 配置，是否放弃这些修改？');}
    function modelOptions(selected) {
        const models=state.options?.models||[];
        const retired=selected&&!models.some(m=>m.modelId===selected);
        return (retired?'<option value="'+esc(selected)+'" selected>原模型已停用，请重新选择</option>':'')+
            (models.map(m=>'<option value="'+esc(m.modelId)+'" '+(m.modelId===selected?'selected':'')+'>'+esc(m.modelName)+(m.tier?' · '+esc(m.tier):'')+'</option>').join('')||'<option value="">暂无开放模型</option>');
    }
    function template(strategy) {
        const t=state.options.templates.find(t=>t.strategy===strategy);
        return copy(t.nodes).map(n=>({...n,modelId:state.options.models[0]?.modelId||'',mcpBindings:[],advisors:[]}));
    }
    function newAgent() {
        state.agent={name:'',description:'',strategy:'fixed',status:1,schemaVersion:2,editable:true,nodes:template('fixed')};
        state.dirty=false;state.draft=null;state.scenes={};state.selected=0;state.tab='prompt';renderAgent();
    }
    function renderList() {
        $('agent-select').innerHTML='<option value="">新建 Agent</option>'+state.agents.map(a=>'<option value="'+esc(a.agentId)+'">'+esc(a.name)+' · '+esc(a.strategy.toUpperCase())+(yes(a.editable)?'':' · 预置')+'</option>').join('');
        $('agent-select').value=state.agent?.agentId||'';
    }
    function renderAgent() {
        const a=state.agent;renderList();renderSaveState();
        $('editor-title').textContent=a.agentId?a.name:'搭建你的 Agent';
        $('agent-name').value=a.name||'';$('agent-description').value=a.description||'';$('agent-enabled').checked=yes(a.status);
        $('readonly-note').hidden=!!editable();$('agent-fields').hidden=!editable();
        $('start-chat').hidden=!a.agentId;$('start-chat').href='/?agentId='+endpoint(a.agentId||'');
        $('archive-agent').hidden=!a.agentId;
        $('draft-mode').textContent='按当前 '+a.strategy.toUpperCase()+' 模式生成';
        if(editable()){drawGraph();renderInspector();}
        renderDraft();refreshEnabled();
    }
    function card(n,index,style='',cls='') {
        if(!n)return `<div class="ta-node ta-missing-node" style="${style}"><strong>节点配置缺失</strong><span class="ta-muted">请重新加载完整的 Flow 配置</span></div>`;
        const executor=state.agent?.nodes?.[flowExecutorIndex()];
        const tools=inheritsFlowTools(n)?(executor?'读取执行器 MCP '+(executor.mcpBindings||[]).length:'缺少执行器配置'):(n.usePublicTools?'公共池按需 · ':'')+'已绑定 MCP '+(n.mcpBindings||[]).length;
        return `<button type="button" class="ta-node ${cls}" style="${style}" data-node="${index}" data-point="n${index}" aria-pressed="${state.selected===index}" aria-label="配置${esc(n.name)}"><span class="ta-node-heading"><span class="ta-node-number">${String(index+1).padStart(2,'0')}</span><span class="ta-node-title">${esc(n.name)}</span></span><span class="ta-node-preview">${esc(n.systemPrompt)}</span><span class="ta-node-meta"><span>独立 Prompt</span><span>${tools}</span><span>${n.advisors.length} Advisor</span></span></button>`;
    }
    const terminal=(id,label,style='')=>`<div class="ta-terminal" data-point="${id}" style="${style}">${label}</div>`;
    function drawGraph() {
        const mode=state.agent.strategy,ns=state.agent.nodes;let html='';state.links=[];
        if(mode==='auto') {
            html=terminal('start','用户输入','grid-column:1/-1')+card(ns[0],0,'grid-row:2;grid-column:1')+card(ns[1],1,'grid-row:2;grid-column:2')+card(ns[2],2,'grid-row:3;grid-column:2')+card(ns[3],3,'grid-row:3;grid-column:1')+terminal('end','最终回答','grid-column:1;grid-row:4');
            state.links=[['start','n0','bottom','top'],['n0','n1','right','left'],['n1','n2','bottom','top'],['n2','n3','left','right','通过'],['n3','end','bottom','top'],['n2','n1','right','right','返工','return']];
        } else if(mode==='fixed') {
            html=terminal('start','用户输入')+ns.map((n,i)=>card(n,i)).join('')+terminal('end','最终回答');
            const ids=['start',...ns.map((_,i)=>'n'+i),'end'];for(let i=0;i<ids.length-1;i++)state.links.push([ids[i],ids[i+1],'bottom','top']);
        } else {
            html=terminal('start','用户目标','grid-column:1/-1')+card(ns[0],0)+card(ns[1],1)+card(ns[2],2,'','ta-flow-executor')+`<div class="ta-runtime" data-point="runtime"><div class="ta-runtime-label">运行时计划示例 · 实际任务由规划节点生成</div><div class="ta-runtime-tasks"><div class="ta-runtime-task">A · 收集证据</div><div class="ta-runtime-task">B · 核对资料</div><div class="ta-runtime-arrows"><span>↘</span><span>↙</span></div><div class="ta-runtime-merge">C · 对比结果 → 整合回答</div></div></div>`;
            state.links=[['start','n0','bottom','top'],['n0','n1','right','left'],['n1','n2','bottom','top','计划'],['n2','runtime','bottom','top']];
        }
        $('ta-board').innerHTML=`<svg class="ta-links" aria-hidden="true"></svg><div class="ta-graph-grid ${mode==='fixed'?'ta-fixed-grid':''}">${html}</div>`;
        const hints={fixed:['Fixed 执行结构','按设定的顺序执行，每步可使用独立配置。','实线：依次执行 · 最多 8 个节点'],auto:['Auto 执行结构','分析、执行、质检、总结，四个角色分别配置。','质检不通过会返工，次数上限由运行时控制'],flow:['Flow 角色与运行计划','配置三个角色；具体任务及依赖在运行时生成。','上方配置角色；下方为动态任务示例']};
        [$('ta-mode-title').textContent,$('ta-mode-hint').textContent,$('ta-canvas-note').textContent]=hints[mode];
        $('add-step').hidden=mode!=='fixed';$('add-step').disabled=ns.length>=8;
        root.querySelectorAll('[data-mode]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.mode===mode)));
        requestAnimationFrame(drawLinks);
    }
    function drawLinks() {
        const board=$('ta-board'),svg=board.querySelector('svg');if(!svg)return;
        const rect=board.getBoundingClientRect();if(!rect.width)return;
        const point=(id,side)=>{const el=board.querySelector(`[data-point="${id}"]`);if(!el)return null;const r=el.getBoundingClientRect();return{x:(side==='left'?r.left:side==='right'?r.right:r.left+r.width/2)-rect.left,y:(side==='top'?r.top:side==='bottom'?r.bottom:r.top+r.height/2)-rect.top};};
        svg.setAttribute('viewBox',`0 0 ${rect.width} ${rect.height}`);
        svg.innerHTML='<defs><marker id="ta-arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><polygon points="0,0 7,3.5 0,7" fill="currentColor"></polygon></marker></defs>'+state.links.map(([a,b,as,bs,label,kind])=>{
            const p=point(a,as),q=point(b,bs);if(!p||!q)return'';let d,lx=(p.x+q.x)/2,ly=(p.y+q.y)/2-7;
            if(kind==='return'){const x=rect.width-3;d=`M${p.x},${p.y} H${x-8} Q${x},${p.y} ${x},${p.y-8} V${q.y+8} Q${x},${q.y} ${x-8},${q.y} H${q.x+3}`;lx=x-8;ly=(p.y+q.y)/2;}
            else if(as==='right'||as==='left')d=`M${p.x},${p.y} L${q.x},${q.y}`;
            else{const mid=(p.y+q.y)/2;d=`M${p.x},${p.y+2} C${p.x},${mid} ${q.x},${mid} ${q.x},${q.y-3}`;}
            return `<path class="${kind==='return'?'ta-return':''}" d="${d}" marker-end="url(#ta-arrow)"></path>${label?`<text x="${lx}" y="${ly}" text-anchor="${kind==='return'?'end':'middle'}">${label}</text>`:''}`;
        }).join('');
    }
    function field(id,label,value,rows=6,max=12000) {
        return `<div class="ta-field"><label for="${id}">${label}</label><textarea id="${id}" class="ta-input" rows="${rows}" maxlength="${max}">${esc(value)}</textarea></div>`;
    }
    function renderInspector() {
        const n=node();if(!n)return;const mode=state.agent.strategy;
        const tabs=[['prompt','Prompt'],['tools','MCP 连接'],['advisors','Advisor'],['io','输入输出']];
        $('ta-inspector').innerHTML=`<div class="ta-inspector-heading"><span class="ta-node-number">${state.selected+1}</span><div><h3>${esc(n.name)}</h3><p class="ta-muted">仅修改这个节点</p></div></div><div class="ta-field"><label for="node-name">节点名称</label><input id="node-name" class="ta-input" maxlength="50" value="${esc(n.name)}"></div><div class="ta-field"><label for="node-model">本节点模型</label><select id="node-model" class="ta-input">${modelOptions(n.modelId)}</select><span class="ta-muted">当前使用平台模型 · 每个节点独立选择</span></div><div class="ta-subtabs" role="tablist" aria-label="节点配置分类">${tabs.map(([id,label])=>`<button type="button" class="ta-subtab" id="tab-${id}" data-tab="${id}" role="tab" aria-selected="${state.tab===id}" aria-controls="node-content">${label}</button>`).join('')}</div><div id="node-content" role="tabpanel" aria-labelledby="tab-${state.tab}"></div>`;
        let html='';
        if(state.tab==='prompt') html=field('node-prompt',n.role==='EXECUTOR_CLIENT'?'最终整合 Prompt':'节点系统 Prompt',n.systemPrompt)+
            (n.role==='EXECUTOR_CLIENT'?field('node-task-prompt','子任务执行指令',n.taskPrompt,4):'')+
            '<div class="ta-contract">运行协议由系统维护<div class="ta-code">'+(mode==='flow'?'计划 JSON、任务依赖、子任务输出协议':mode==='auto'?'分析步骤、质检字段、返工条件':'上一步输出 → 下一步输入')+'</div></div><p class="ta-note">这里只定义该节点的业务要求；每个节点分别保存。</p>';
        if(state.tab==='tools') html=renderTools(n);
        if(state.tab==='advisors') html=renderAdvisors(n);
        if(state.tab==='io') {
            const ins=mode==='fixed'?(state.selected===0?'用户消息':'上一个顺序节点的输出'):mode==='auto'?['用户目标 + 会话上下文','分析策略 + 用户目标 + 返工意见','执行结果 + 用户目标','执行记录 + 质量评估'][state.selected]:['用户目标 + 执行器工具能力','能力分析 + 用户目标','计划 + 当前任务 + 依赖步骤结果'][state.selected];
            html='<div class="ta-field"><label>本节点接收</label><div class="ta-contract">'+ins+'</div></div>'+field('node-output','输出要求',n.outputRequirement,5,4000)+'<p class="ta-note">输出要求补充到本节点 Prompt，不改变模式的流程控制。</p>';
        }
        if(mode==='fixed') html+=`<div class="ta-actions"><button type="button" class="ta-btn" data-order="-1" ${state.selected===0?'disabled':''}>↑ 前移</button><button type="button" class="ta-btn" data-order="1" ${state.selected===state.agent.nodes.length-1?'disabled':''}>↓ 后移</button><button type="button" class="ta-btn danger" id="remove-node" ${state.agent.nodes.length===1?'disabled':''}>移除节点</button></div>`;
        $('node-content').innerHTML=html;
    }
    function renderTools(n) {
        if(inheritsFlowTools(n))return renderInheritedFlowTools();
        if(!executable(n)) return '<p class="ta-note">此角色不执行业务工具。请在执行节点配置 MCP；Flow 的能力分析与规划读取执行器授权的工具清单。</p>';
        const connections=[...state.mcps];
        for(const binding of n.mcpBindings)if(!connections.some(m=>m.mcpId===binding.mcpId))connections.push({mcpId:binding.mcpId,name:'已保存的连接（当前不可用）',status:0,usable:false});
        let html=`<label class="ta-check ta-public-pool"><input id="node-public-tools" type="checkbox" ${n.usePublicTools?'checked':''}><span><strong>允许使用公共工具池</strong><small>允许模型从平台公共工具池按需寻找工具，不会把整个工具池预先绑定到节点。下方是你明确绑定的 MCP 连接；关闭此开关不会移除这些绑定。</small></span></label><div class="ta-section-head"><strong>已绑定 MCP · ${n.mcpBindings.length}</strong><span class="ta-muted">按连接授权 · 运行时按需加载</span></div>`;
        html+=connections.map(m=>{
            const binding=n.mcpBindings.find(b=>b.mcpId===m.mcpId), known=state.tools[m.mcpId];
            const restricted=restrictedBinding(binding);
            return `<div class="ta-tool-group ${binding?'ta-mcp-bound':''}" data-connection="${esc(m.mcpId)}"><div class="ta-tool-title"><strong>${esc(m.name)}</strong><span class="ta-tag">${usableMcp(m)?(yes(m.editable)?esc(m.transportType):'平台托管'):'不可用'}</span>${binding?'<span class="ta-tag ta-bound-tag">已绑定 MCP</span>':''}</div><p class="ta-note">${restricted?'保留原有的逐工具授权限制。':binding?'模型会在运行时按需加载此连接的具体工具。':'直接绑定此连接，无需先发现或逐个选择工具。'}</p><div class="ta-mcp-actions">${binding?`<button type="button" class="ta-btn" data-unbind="${esc(m.mcpId)}">解除绑定</button>`:`<button type="button" class="ta-btn ta-primary" data-bind-mcp="${esc(m.mcpId)}" ${!usableMcp(m)?'disabled':''}>绑定到当前节点</button>`}${state.mcps.some(c=>c.mcpId===m.mcpId)?`<button type="button" class="ta-link" data-edit-mcp="${esc(m.mcpId)}">${yes(m.editable)?'编辑连接':'查看平台连接'}</button>`:''}</div>${restricted?`<div class="ta-legacy-binding"><strong>旧配置 · 限定工具</strong><p>${(binding.toolNames||[]).map(name=>'<code>'+esc(name)+'</code>').join(' ')||'未授权具体工具'}</p><p class="ta-note">直接保存会保留以上限制。转换后将授权此连接的全部工具，包括以后新增的工具。</p><button type="button" class="ta-btn" data-convert-binding="${esc(m.mcpId)}" ${!usableMcp(m)?'disabled':''}>改为按连接绑定</button></div>`:''}${!usableMcp(m)?'<p class="ta-note">'+esc(m.unavailableReason||'连接已停用，请检查状态或联系管理员。')+'</p>':''}<details class="ta-tool-preview" ${known?'open':''}><summary>查看工具 · 可选诊断</summary><p class="ta-note">预览不会改变绑定或授权范围；无需执行此操作也能保存 Agent。</p><button type="button" class="ta-btn" data-discover="${esc(m.mcpId)}" ${!usableMcp(m)?'disabled':''}>读取 / 刷新工具列表</button>${known?`<ul>${known.map(t=>`<li><strong>${esc(t.name)}</strong><p>${esc(t.description)}</p></li>`).join('')}</ul>${!known.length?'<p class="ta-note">未返回工具，可稍后重试。</p>':''}`:''}</details></div>`;
        }).join('');
        html+='<button type="button" class="ta-btn" id="inline-add-mcp">＋ 添加 MCP 连接</button><p class="ta-note">连接保存在“我的 MCP”中，每个节点独立绑定。保存 Agent 后生效。</p>';
        return html;
    }
    function renderInheritedFlowTools() {
        const executor=state.agent.nodes[flowExecutorIndex()];
        if(!executor)return '<section class="ta-inherited-mcp"><div class="ta-section-head"><strong>未找到执行器节点</strong><span class="ta-tag">只读</span></div><p class="ta-note">当前 Flow 配置缺少执行器，暂时无法显示共享的 MCP 授权。请重新加载完整配置。</p><button type="button" class="ta-btn ta-primary" id="configure-flow-executor" disabled>前往执行器配置</button></section>';
        const bindings=executor.mcpBindings||[],publicPool=yes(executor.usePublicTools);
        return `<section class="ta-inherited-mcp" aria-label="来自执行器的 MCP 配置"><div class="ta-section-head"><strong>来自「${esc(executor.name)}」</strong><span class="ta-tag">只读共享</span></div><p class="ta-note">能力分析与步骤规划读取执行器授权的工具范围，实际工具调用由执行器完成。请统一在执行器中修改。</p><div class="ta-inherited-pool"><strong>公共工具池</strong><span class="ta-tag ${publicPool?'ta-bound-tag':''}" data-inherited-pool>${publicPool?'已开启':'已关闭'}</span><p class="ta-note">${publicPool?'执行器可从公共工具池按需寻找工具，与下方显式绑定的连接一同构成可用范围。':'执行器只使用明确绑定并授权的 MCP 连接。'}</p></div><div class="ta-section-head"><strong>执行器已绑定 MCP · ${bindings.length}</strong></div>${bindings.length?`<ul class="ta-inherited-connections">${bindings.map(binding=>{const m=state.mcps.find(m=>m.mcpId===binding.mcpId);return `<li data-inherited-binding><div class="ta-section-head"><strong>${esc(m?.name||'已保存的连接（当前不可用）')}</strong><span class="ta-tag ${usableMcp(m)?'ta-bound-tag':''}">${usableMcp(m)?'可使用':'不可用'}</span></div><p class="ta-note">${restrictedBinding(binding)?'旧配置：仅授权 '+(binding.toolNames||[]).length+' 个指定工具，原有限制保持不变。':'按连接授权，具体工具在运行时按需加载。'}</p></li>`;}).join('')}</ul>`:`<p class="ta-note" data-inherited-empty>${publicPool?'尚未显式绑定连接，当前仅允许从公共工具池按需选择。':'执行器尚未绑定 MCP，公共工具池也未开启。'}</p>`}<button type="button" class="ta-btn ta-primary" id="configure-flow-executor">前往执行器配置 MCP</button><p class="ta-note ta-inherited-draft">这里同步显示当前编辑内容；未保存的修改需保存 Agent 后才在运行时生效。</p></section>`;
    }
    function renderAdvisors(n) {
        return state.options.advisors.map(option=>{
            const a=n.advisors.find(a=>a.type===option.type);let settings='';
            if(a?.type==='ChatMemory')settings=`<label>上下文消息数<input class="ta-input" type="number" min="2" max="100" data-param="maxMessages" data-type="${a.type}" value="${a.maxMessages}"></label>`;
            if(a&&['RagAnswer','LongTermMemory','EpisodicMemory'].includes(a.type))settings+=`<label>检索条数<input class="ta-input" type="number" min="1" max="20" data-param="topK" data-type="${a.type}" value="${a.topK}"></label>`;
            if(a?.type==='RagAnswer')settings+=`<label>本人的知识库<select class="ta-input" data-param="knowledgeTag" data-type="${a.type}"><option value="">请选择知识库</option>${state.options.knowledge.map(k=>`<option value="${esc(k.knowledgeTag)}" ${k.knowledgeTag===a.knowledgeTag?'selected':''}>${esc(k.name||k.knowledgeTag)}</option>`).join('')}</select></label>`;
            return `<div class="ta-advisor"><label class="ta-check"><input type="checkbox" data-advisor="${esc(option.type)}" ${a?'checked':''}><span><strong>${esc(option.name)}</strong><small>${esc(option.description)}</small></span></label>${settings?'<div class="ta-advisor-settings">'+settings+'</div>':''}</div>`;
        }).join('');
    }
    async function discover(id) {
        notify('正在连接 MCP 并读取工具列表…');
        const tools=await send('/mcps/'+endpoint(id)+'/discover','POST');state.tools[id]=tools;
        if(editable()){drawGraph();renderInspector();}
        notify(tools.length?'已读取 '+tools.length+' 个工具，仅供预览；节点绑定保持不变。':'未发现工具，请检查服务是否可达、协议和令牌是否正确。',tools.length?'success':'error');
        return tools;
    }
    function fillMcp(m=null) {
        state.mcp=m;state.mcpDirty=false;
        $('mcp-name').value=m?.name||'';$('mcp-url').value=m?.url||'';$('mcp-transport').value=m?.transportType||'streamable';
        $('mcp-token').value='';$('mcp-clear-secret').checked=false;$('mcp-enabled').checked=!m||yes(m.status);
        const managed=!!m&&!yes(m.editable);
        $('managed-mcp').hidden=!managed;$('mcp-fields').hidden=managed;$('save-mcp').hidden=managed;
        $('managed-mcp-name').textContent=managed?m.name:'';
        $('managed-mcp-status').textContent=usableMcp(m)?'可使用':m?.unavailableReason||'已停用';
        $('select-managed-tools').hidden=!m;
        const alreadyBound=node()?.mcpBindings?.some(b=>b.mcpId===m?.mcpId);
        $('select-managed-tools').textContent=alreadyBound?'已绑定到当前节点':'绑定到当前节点';
        $('mcp-bind-note').textContent=!editable()?'请先打开可编辑的 Agent。':!executable(node())?'当前角色不执行业务工具，请先关闭窗口并选择执行节点。':'绑定到「'+node().name+'」节点，保存 Agent 后生效。';
        $('clear-secret-row').hidden=!m?.hasSecret;
        $('mcp-secret-note').textContent=m?.hasSecret?'已有凭据；留空保留，填写替换，勾选可清除。':'保存后不回显凭据。';
        $('remove-mcp').hidden=!m||!yes(m.editable);
        const hosts=state.options.mcpAllowedHosts||[];
        $('mcp-host-notice').hidden=managed;
        $('mcp-host-notice').textContent=hosts.length?'支持 HTTPS；可连接域名：'+hosts.join('、'):'尚未开放远程服务域名，请联系管理员配置允许的 MCP 域名。';
        $('mcp-result').textContent='';$('mcp-result').className='';
        $('mcp-connections').innerHTML=state.mcps.map(c=>`<button type="button" data-select-mcp="${esc(c.mcpId)}">${esc(c.name)}${yes(c.editable)?'':' · 平台托管'}</button>`).join(' ');
        refreshEnabled();
    }
    function openMcp(m=null) {fillMcp(m);$('mcp-dialog').showModal();}
    function closeMcp() {if(state.busy)return;if(state.mcpDirty&&!confirm('连接配置尚未保存，是否放弃修改？'))return;state.mcpDirty=false;$('mcp-token').value='';$('mcp-dialog').close();}
    async function saveMcp(event) {
        event.preventDefault();
        await work(async()=>{
            const m=state.mcp,body={name:$('mcp-name').value.trim(),url:$('mcp-url').value.trim(),transportType:$('mcp-transport').value,status:$('mcp-enabled').checked?1:0};
            if(m)body.version=m.version;
            if($('mcp-clear-secret').checked)body.bearerToken='';else if($('mcp-token').value||!m)body.bearerToken=$('mcp-token').value;
            try{
                const saved=await send('/mcps'+(m?'/'+endpoint(m.mcpId):''),m?'PUT':'POST',body);
                state.mcps=[saved,...state.mcps.filter(c=>c.mcpId!==saved.mcpId)];delete state.tools[saved.mcpId];fillMcp(saved);
                $('mcp-result').className='';$('mcp-result').textContent=usableMcp(saved)?'连接已保存。可直接绑定到节点，具体工具在运行时按需加载。':'连接已保存并停用。';
                if(editable())renderInspector();
            }catch(e){$('mcp-result').className='dialog-error';$('mcp-result').textContent=e.message;throw e;}
        });
    }
    function renderDraft() {
        const d=state.draft;$('draft-preview').hidden=!d;if(!d)return;
        $('draft-preview').innerHTML=`<div class="ta-section-head"><strong>${esc(d.name)} · 待应用</strong><span class="ta-tag">未保存</span></div>${d.nodes.map(n=>`<div class="ta-draft-row"><strong>${esc(n.name)}</strong><span>${esc(n.systemPrompt)}${n.taskPrompt?'\n子任务：'+esc(n.taskPrompt):''}${n.outputRequirement?'\n输出：'+esc(n.outputRequirement):''}</span></div>`).join('')}<p class="ta-note">应用会替换当前节点骨架；不会自动连接工具、保存或开始对话。</p><div class="ta-actions"><button type="button" class="ta-btn ta-primary" data-draft-action="apply">应用到画布</button><button type="button" class="ta-btn" data-draft-action="cancel">取消</button></div>`;
    }
    async function load() {
        if(!discard())return;
        await work(async()=>{
            const me=await request('/api/v1/auth/me');$('account').textContent=me.username||'';
            document.querySelectorAll('[data-admin-only]').forEach(el=>el.hidden=me.role!=='ADMIN');
            const [options,agents,mcps,publicAgents]=await Promise.all([request('/options'),request('/agents'),request('/mcps'),request('/public-agents')]);
            Object.assign(state,{options,agents,mcps,tools:{}});$('draft-model').innerHTML=modelOptions();
            $('public-agents').innerHTML=publicAgents.map(a=>`<article class="public-card"><span class="ta-tag">${esc(a.strategy.toUpperCase())}</span><h3>${esc(a.name)}</h3><p>${esc(a.description)}</p><button type="button" class="ta-btn" data-use-public="${esc(a.agentId)}">使用这个 Agent ↗</button></article>`).join('')||'<p class="ta-muted">暂无公共 Agent</p>';
            newAgent();notify('工作空间已就绪。选择模式并点击节点，开始配置。');
        });
    }
    root.addEventListener('input',event=>{
        if(!editable()||state.busy)return;const el=event.target,n=node();
        const fields={'node-name':'name','node-prompt':'systemPrompt','node-task-prompt':'taskPrompt','node-output':'outputRequirement'};
        if(fields[el.id]){n[fields[el.id]]=el.value;dirty();drawGraph();if(el.id==='node-name')$('ta-inspector').querySelector('h3').textContent=el.value;}
        if(el.id==='agent-name'||el.id==='agent-description'){state.agent[el.id==='agent-name'?'name':'description']=el.value;dirty();}
    });
    root.addEventListener('change',event=>{
        if(!editable()||state.busy)return;const el=event.target,n=node();
        if(el.id==='node-model'){n.modelId=el.value;dirty();}
        if(el.id==='agent-enabled'){state.agent.status=el.checked?1:0;dirty();}
        if(el.id==='node-public-tools'){n.usePublicTools=el.checked;dirty();drawGraph();}
        if(el.dataset.advisor){const type=el.dataset.advisor;n.advisors=n.advisors.filter(a=>a.type!==type);if(el.checked)n.advisors.push({type,...(type==='ChatMemory'?{maxMessages:20}:{}),...(['RagAnswer','LongTermMemory','EpisodicMemory'].includes(type)?{topK:4}:{}),...(type==='RagAnswer'?{knowledgeTag:''}:{})});dirty();drawGraph();renderInspector();}
        if(el.dataset.param){const a=n.advisors.find(a=>a.type===el.dataset.type);a[el.dataset.param]=el.dataset.param==='knowledgeTag'?el.value:Number(el.value);dirty();}
    });
    root.addEventListener('click',event=>{
        const b=event.target.closest('button');if(!b||state.busy)return;
        if(b.dataset.node!==undefined){state.selected=Number(b.dataset.node);drawGraph();renderInspector();}
        if(b.dataset.tab){state.tab=b.dataset.tab;renderInspector();}
        if(b.id==='configure-flow-executor'&&flowExecutorIndex()>=0){state.selected=flowExecutorIndex();state.tab='tools';drawGraph();renderInspector();}
        if(b.dataset.mode&&editable()&&b.dataset.mode!==state.agent.strategy){
            state.scenes[state.agent.strategy]=copy(state.agent.nodes);state.agent.strategy=b.dataset.mode;
            state.agent.nodes=state.scenes[b.dataset.mode]||template(b.dataset.mode);state.selected=0;state.draft=null;dirty();renderAgent();}
        if(b.id==='add-step'&&state.agent.nodes.length<8){const n=template('fixed')[0];n.nodeId='n_'+Array.from(crypto.getRandomValues(new Uint32Array(4)),x=>x.toString(16)).join('_');n.name='处理步骤 '+(state.agent.nodes.length+1);state.agent.nodes.push(n);state.selected=state.agent.nodes.length-1;dirty();drawGraph();renderInspector();}
        if(b.dataset.order){const next=state.selected+Number(b.dataset.order);if(next<0||next>=state.agent.nodes.length)return;[state.agent.nodes[state.selected],state.agent.nodes[next]]=[state.agent.nodes[next],node()];state.selected=next;dirty();drawGraph();renderInspector();}
        if(b.id==='remove-node'&&state.agent.nodes.length>1&&confirm('移除此节点及其配置？')){state.agent.nodes.splice(state.selected,1);state.selected=Math.max(0,state.selected-1);dirty();drawGraph();renderInspector();}
        if(b.dataset.discover)work(()=>discover(b.dataset.discover));
        if(b.dataset.bindMcp)bindMcp(b.dataset.bindMcp);
        if(b.dataset.convertBinding&&executable(node())&&confirm('改为按连接绑定会授权该连接的全部工具，包括以后新增的工具。是否转换？')){node().mcpBindings=node().mcpBindings.map(binding=>binding.mcpId===b.dataset.convertBinding?connectionBinding(binding.mcpId):binding);dirty();drawGraph();renderInspector();}
        if(b.dataset.unbind&&executable(node())){node().mcpBindings=node().mcpBindings.filter(m=>m.mcpId!==b.dataset.unbind);dirty();drawGraph();renderInspector();}
        if(b.id==='inline-add-mcp')openMcp();
        if(b.dataset.editMcp)openMcp(state.mcps.find(m=>m.mcpId===b.dataset.editMcp));
        if(b.dataset.draftAction==='cancel'){state.draft=null;renderDraft();}
        if(b.dataset.draftAction==='apply'&&state.draft&&confirm('将草稿应用到当前 Agent，替换节点配置？')){
            const previous=state.agent;state.agent={...copy(state.draft),agentId:previous.agentId,version:previous.version,editable:true};
            state.draft=null;state.scenes={};state.selected=0;state.tab='prompt';dirty();renderAgent();notify('骨架已应用，尚未保存。可以继续逐节点调整。','success');}
        if(b.dataset.usePublic&&discard())work(async()=>{const result=await send('/public-agents/'+endpoint(b.dataset.usePublic)+'/use','POST');state.dirty=false;location.href='/?agentId='+endpoint(result.agentId);});
    });
    $('agent-select').addEventListener('change',()=>{
        const id=$('agent-select').value;if(!discard()){$('agent-select').value=state.agent?.agentId||'';return;}
        if(!id){newAgent();return;}
        work(async()=>{state.agent=await request('/agents/'+endpoint(id));state.selected=0;state.tab='prompt';state.dirty=false;state.draft=null;state.scenes={};renderAgent();});
    });
    $('new-agent').onclick=()=>{if(discard())newAgent();};$('reload').onclick=load;
    $('save-agent').onclick=()=>work(async()=>{
        const a=state.agent;
        if(!a.name.trim())throw new Error('请填写 Agent 名称');
        for(const n of a.nodes){if(!n.name.trim()||!n.systemPrompt.trim()||!n.modelId)throw new Error('请为每个节点填写名称、Prompt 并选择模型');if(n.advisors.some(v=>v.type==='RagAnswer'&&!v.knowledgeTag))throw new Error('请为知识库 Advisor 选择本人的知识库');}
        const body=copy(a);
        for(const n of body.nodes)n.mcpBindings=n.mcpBindings.map(binding=>yes(binding.allTools)?connectionBinding(binding.mcpId):binding);
        const saved=await send('/agents'+(a.agentId?'/'+endpoint(a.agentId):''),a.agentId?'PUT':'POST',body);
        state.agent=saved;state.dirty=false;state.scenes={};state.agents=await request('/agents');renderAgent();notify('Agent 已保存。节点配置将在下一次运行时生效。','success');
    });
    $('archive-agent').onclick=()=>{if(state.agent.agentId&&confirm('归档此 Agent？历史对话保留。'))work(async()=>{await send('/agents/'+endpoint(state.agent.agentId),'DELETE');state.agents=await request('/agents');newAgent();notify('Agent 已归档。');});};
    $('draft-form').onsubmit=event=>{event.preventDefault();work(async()=>{notify('正在生成节点骨架，画布配置会保留…');state.draft=await send('/draft','POST',{requirement:$('draft-requirement').value.trim(),modelId:$('draft-model').value,strategy:state.agent.strategy});renderDraft();notify('骨架已生成。检查预览后，可以应用到画布。','success');});};
    $('manage-mcps').onclick=()=>openMcp(state.mcps.find(m=>yes(m.editable))||state.mcps[0]||null);
    $('discover-managed-mcp').onclick=()=>work(async()=>{
        $('mcp-result').className='';$('mcp-result').textContent='正在读取平台工具列表…';
        try{const tools=await discover(state.mcp.mcpId);$('mcp-result').textContent=tools.length?'已发现 '+tools.length+' 个工具：'+tools.map(t=>t.name).join('、')+'。此预览不会改变节点绑定。':'平台连接未返回工具，请重试或联系管理员。';}
        catch(e){$('mcp-result').className='dialog-error';$('mcp-result').textContent=e.message;throw e;}
    });
    function bindMcp(id) {
        if(state.busy||!editable()||!executable(node())||!usableMcp(state.mcps.find(m=>m.mcpId===id)))return;
        if(node().mcpBindings.some(b=>b.mcpId===id))return;
        node().mcpBindings.push(connectionBinding(id));state.tab='tools';dirty();drawGraph();renderInspector();
        notify('已绑定 MCP 到「'+node().name+'」节点。保存 Agent 后生效。','success');
    }
    $('select-managed-tools').onclick=()=>{
        if(state.busy||!editable()||!executable(node())||!usableMcp(state.mcp))return;
        const id=state.mcp.mcpId;closeMcp();bindMcp(id);
    };
    $('mcp-form').onsubmit=saveMcp;$('mcp-form').oninput=()=>state.mcpDirty=true;
    $('new-mcp').onclick=()=>{if(!state.mcpDirty||confirm('放弃未保存的连接修改？'))fillMcp();};
    $('mcp-connections').onclick=event=>{const b=event.target.closest('[data-select-mcp]');if(b&&!state.busy&&(!state.mcpDirty||confirm('放弃未保存的连接修改？')))fillMcp(state.mcps.find(m=>m.mcpId===b.dataset.selectMcp));};
    $('close-mcp').onclick=closeMcp;$('mcp-dialog').addEventListener('cancel',event=>{event.preventDefault();closeMcp();});
    $('remove-mcp').onclick=()=>{const m=state.mcp;if(m&&confirm('删除连接？仍被已保存的 Agent 使用时需要先解除关联。'))work(async()=>{
        try{await send('/mcps/'+endpoint(m.mcpId),'DELETE');state.mcps=state.mcps.filter(c=>c.mcpId!==m.mcpId);delete state.tools[m.mcpId];fillMcp();if(editable()){for(const n of state.agent.nodes)n.mcpBindings=n.mcpBindings.filter(b=>b.mcpId!==m.mcpId);dirty();drawGraph();renderInspector();}notify('连接已删除。');}
        catch(e){$('mcp-result').textContent=e.message;$('mcp-result').className='dialog-error';throw e;}
    });};
    window.addEventListener('beforeunload',event=>{if(state.dirty||state.mcpDirty){event.preventDefault();event.returnValue='';}});
    new ResizeObserver(()=>requestAnimationFrame(drawLinks)).observe($('ta-board'));
    load();
})();
