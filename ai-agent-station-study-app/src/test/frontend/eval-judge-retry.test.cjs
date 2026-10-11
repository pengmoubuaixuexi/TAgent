const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/eval.html'), 'utf8');
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];
const source = script.slice(script.indexOf('const API=')).replace(/\binit\(\);\s*$/, '');
const original = (extra = {}) => ({judgeJobId:'judge-original',evalRunId:'run-a',status:'PARTIAL',modelId:'deepseek-v4-flash',completedCases:99,totalCases:100,failedCases:1,averageScore:80,results:[{resultId:'r46',stableKey:'Q46',caseStatus:'PASS',status:'FAILED',errorMessage:'Judge 返回为空'}],...extra});
const retry = (extra = {}) => ({judgeJobId:'judge-retry',evalRunId:'run-a',status:'QUEUED',completedCases:0,totalCases:1,failedCases:0,config:{retryOfJudgeJobId:'judge-original'},effectiveCompletedCases:99,effectiveTotalCases:100,effectiveFailedCases:1,effectiveAverageScore:80,...extra});
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
function screen(handler = () => retry()){
    const nodes=new Map(),calls=[],notices=[],downloads=[];
    const get=id=>{if(!nodes.has(id)){const classes=new Set(['detailModal','judgeModal'].includes(id)?['hidden']:[]);nodes.set(id,{innerHTML:'',textContent:'',value:'',disabled:false,classList:{add:x=>classes.add(x),remove:x=>classes.delete(x),contains:x=>classes.has(x),toggle(x,flag){if(flag??!classes.has(x))classes.add(x);else classes.delete(x)}}})}return nodes.get(id)};
    const context=vm.createContext({document:{getElementById:get,addEventListener(){}},clearInterval(){},setInterval(){return 1},fetch:async(url,options={})=>{const route=url.replace('/api/v1/eval','');calls.push({route,options});const data=await handler(route,options);return {ok:true,json:async()=>({code:'0000',data})}}});
    vm.runInContext(source,context);
    context.captureNotice=(...args)=>notices.push(args);context.captureDownload=(...args)=>downloads.push(args);
    vm.runInContext("toast=captureNotice;downloadJsonFile=captureDownload;renderRunSidebar=()=>{};state.selectedRun='run-a';state.run={evalRunId:'run-a',status:'COMPLETED',results:[]};",context);
    const state=vm.runInContext('state',context);state.judgeJobs=[original()];state.currentJudge=state.judgeJobs[0];
    return {context,state,get,calls,notices,downloads,posts:()=>calls.filter(c=>c.options.method==='POST')};
}
function routes(post){return (route,options)=>options.method==='POST'?post():route==='/runs/run-a'?{evalRunId:'run-a',status:'JUDGING',results:[]}:route==='/runs/run-a/judge-jobs'?[retry(),original()]:retry()}

test('retry uses Judge failures despite successful Agent execution, and terminal success or cancellation has no retry',async()=>{
    const ui=screen(routes(()=>retry()));
    assert.match(ui.context.renderJudgeJobs(),/补评失败题（1）/);
    await ui.context.retryFailedJudge('judge-original');
    assert.equal(ui.posts().length,1);
    assert.equal(ui.posts()[0].route,'/judge-jobs/judge-original/retry-failed');
    assert.equal(ui.posts()[0].options.body,undefined,'retry model and failed IDs must be resolved by backend');
    assert.match(ui.notices[0][0],/已成功的评分会保留/);
    for(const job of [original({status:'COMPLETED',failedCases:0}),original({status:'CANCELLED'}),original({status:'RUNNING'})]){
        assert.equal(ui.context.renderJudgeRetryButton(job),'');
    }
});

test('combined summary displays 100 scores instead of a one-question average, including JSON export',async()=>{
    const ui=screen();
    const result=retry({status:'COMPLETED',completedCases:1,averageScore:20,effectiveCompletedCases:100,effectiveFailedCases:0,effectiveAverageScore:79.4,results:[{resultId:'r1',sourceJudgeJobId:'judge-original',status:'COMPLETED',overall:80,executionTrace:{}},{resultId:'r46',sourceJudgeJobId:'judge-retry',status:'COMPLETED',overall:20,executionTrace:{}}]});
    ui.state.judgeJobs=[result];ui.state.currentJudge=result;
    const listing=ui.context.renderJudgeJobs();
    assert.match(listing,/100\/100/);assert.match(listing,/79\.4/);assert.match(listing,/本次补评 1\/1/);assert.doesNotMatch(listing,/补评失败题/);
    ui.context.renderJudgeDetail();assert.match(ui.get('detailBody').innerHTML,/100\/100/);assert.match(ui.get('detailBody').innerHTML,/已合并原批次成功评分/);
    await ui.context.downloadJudgeBatch();
    assert.equal(ui.downloads[0][1].averageScore,79.4);assert.equal(ui.downloads[0][1].totalCases,100);
    assert.equal(ui.downloads[0][1].results[0].llmJudge.sourceJudgeJobId,'judge-original');
});

test('failed selected-question attempt shows escaped diagnostics while retaining the previous valid score',async()=>{
    const ui=screen();
    const diagnostics={attempts:[{finishReason:'length',contentLength:0,requestId:'request-46',failure:'<script>unsafe</script>'}]};
    const job=retry({status:'FAILED',config:{baseJudgeJobId:'judge-original'},failedCases:1,effectiveCompletedCases:100,effectiveFailedCases:0,effectiveAverageScore:80,results:[{resultId:'r46',stableKey:'Q46',sourceJudgeJobId:'judge-original',status:'COMPLETED',overall:80,executionTrace:{}}],attemptResults:[{resultId:'r46',stableKey:'Q46',sourceJudgeJobId:'judge-retry',status:'FAILED',errorMessage:'Judge 返回为空',rawResponse:diagnostics}]});
    ui.state.currentJudge=job;ui.state.judgeJobs=[job];ui.context.renderJudgeDetail();
    const detail=ui.get('detailBody').innerHTML;
    assert.match(detail,/<details class="card section"><summary>本次补评尝试记录（1 题，1 题失败）/);
    assert.match(detail,/本次补评选定题目/);assert.match(detail,/Judge 返回为空/);assert.match(detail,/finishReason/);
    assert.match(detail,/&lt;script&gt;unsafe&lt;\/script&gt;/);assert.doesNotMatch(detail,/<script>|<details[^>]*\bopen\b/);
    assert.match(detail,/>80<\/td>/);assert.match(detail,/100\/100/);
    assert.match(ui.context.renderJudgeJobs(),/选定题目补评/);
    await ui.context.downloadJudgeBatch();
    const exported=ui.downloads[0][1];
    assert.equal(exported.results[0].llmJudge.status,'COMPLETED');assert.equal(exported.results[0].llmJudge.overall,80);
    assert.equal(exported.attemptResults[0].status,'FAILED');assert.equal(exported.attemptResults[0].rawResponse,diagnostics);
    assert.equal(exported.baseJudgeJobId,'judge-original');
});

test('pending retry blocks double-clicks, other retry submissions and new Judge submissions',async()=>{
    const wait=deferred(),ui=screen(routes(()=>wait.promise));
    const pending=ui.context.retryFailedJudge('judge-original');
    assert.match(ui.context.renderJudgeRetryButton(ui.state.currentJudge),/disabled/);
    assert.match(ui.context.renderJudgeRetryButton(ui.state.currentJudge),/正在提交补评/);
    await ui.context.retryFailedJudge('judge-original');
    vm.runInContext("judgePicker.runId='run-a';judgePicker.status='ready';judgePicker.models=[{modelName:'x',apiId:'y'}];document.getElementById('judgeModal').classList.remove('hidden');document.getElementById('judgeModel').value='0';",ui.context);
    await ui.context.createJudge();assert.equal(ui.posts().length,1);
    wait.resolve(retry());await pending;
    await ui.context.retryFailedJudge('judge-original');assert.equal(ui.posts().length,1,'queued child blocks another submission');
});

test('active Judge job blocks retry even if run status has not yet changed',async()=>{
    const ui=screen();ui.state.judgeJobs.unshift(retry({status:'RUNNING'}));
    assert.match(ui.context.renderJudgeRetryButton(ui.state.currentJudge),/disabled/);
    await ui.context.retryFailedJudge('judge-original');assert.equal(ui.posts().length,0);
});

test('failed retry retains previous scores and allows an explicit retry with the backend error shown',async()=>{
    const ui=screen(()=>{throw new Error('该 Judge 已有更新记录，请选择最新记录补评')});
    await ui.context.retryFailedJudge('judge-original');
    assert.equal(ui.state.currentJudge.completedCases,99);assert.equal(ui.state.currentJudge.averageScore,80);
    assert.doesNotMatch(ui.context.renderJudgeRetryButton(ui.state.currentJudge),/disabled/);
    assert.match(ui.notices.at(-1)[0],/请选择最新记录/);
    await ui.context.retryFailedJudge('judge-original');assert.equal(ui.posts().length,2);
});

test('retry completing after navigation cannot reopen or overwrite another run',async()=>{
    const wait=deferred(),ui=screen(()=>wait.promise);ui.get('detailModal').classList.remove('hidden');
    const pending=ui.context.retryFailedJudge('judge-original');
    ui.state.selectedRun='run-b';ui.state.run={evalRunId:'run-b',status:'COMPLETED'};ui.state.judgeJobs=[];
    wait.resolve(retry());await pending;
    assert.equal(ui.state.selectedRun,'run-b');assert.equal(ui.state.judgeJobs.length,0);assert.equal(ui.calls.length,1);assert.equal(ui.notices.length,0);
});

test('retry opened from detail moves to the combined child result after submission',async()=>{
    const ui=screen(routes(()=>retry()));ui.get('detailModal').classList.remove('hidden');
    await ui.context.retryFailedJudge('judge-original');
    assert.equal(ui.state.currentJudge.judgeJobId,'judge-retry');assert.match(ui.get('detailBody').innerHTML,/99\/100/);
    assert.equal(ui.get('detailModal').classList.contains('hidden'),false);
});

test('out-of-order Judge detail responses cannot overwrite a newer detail or different run',async()=>{
    const wait=deferred(),ui=screen(route=>route.endsWith('judge-original')?wait.promise:retry());
    const first=ui.context.openJudgeDetail('judge-original');await ui.context.openJudgeDetail('judge-retry');
    wait.resolve(original());await first;assert.equal(ui.state.currentJudge.judgeJobId,'judge-retry');
    const slow=deferred(),other=screen(()=>slow.promise);const loading=other.context.openJudgeDetail('judge-original');other.state.selectedRun='run-b';slow.resolve(original());await loading;
    assert.equal(other.get('detailModal').classList.contains('hidden'),true);
});
