(() => {
  'use strict';
  const $=id=>document.getElementById(id), number=value=>Number(value||0).toLocaleString('zh-CN');
  const runId=(new URLSearchParams(location.search).get('runId')||'').trim();
  const sourceLabel=value=>({PERSONAL:'个人提供商账号',PLATFORM:'平台试用',SYSTEM:'系统服务'}[value]||value);
  function text(tag,value,cls){const el=document.createElement(tag);el.textContent=value;if(cls)el.className=cls;return el;}
  async function refresh(){
    $('refresh').disabled=true;
    try {
      const response=await fetch('/api/v1/workspace/usage'+(runId?'?'+new URLSearchParams({runId}):''),{cache:'no-store'}),result=await response.json();
      if(!response.ok||result.code!=='0000')throw new Error(result.info||'用量加载失败');
      const d=result.data,t=d.today,l=d.limits;
      $('day').textContent=d.day+' · '+d.dayTimeZone;
      // Yesterday's overrun counts against today; at the quota new tasks wait, running ones finish their phase.
      const carried=Number(t.carriedTokens||0);
      $('tokenQuota').textContent=number(Number(t.platformReservedTokens||0)+carried)+' / '+number(l.platformDailyTokens)
        +(carried>0?'（含昨日超出 '+number(carried)+'）':'');
      $('callQuota').textContent=number(t.platformCalls)+' / '+number(l.platformDailyCalls);
      $('runQuota').textContent=number(d.activeRuns)+' / '+number(l.concurrentRuns);
      $('confirmed').textContent=number(t.confirmedPlatformTokens);
      $('runFilter').hidden=!d.filteredRunId;
      if(d.filteredRunId){
        $('filterLabel').textContent='筛选运行：'+d.filteredRunId;
        const s=d.runSummary;
        $('runSummary').textContent=`本运行全部请求：${number(s.calls)} · 已确认 Token：${number(s.confirmedTokens)} · 用量未知：${number(s.unknownCalls)} · 待结算：${number(s.pendingCalls)} · 价格未知\n费用来源：${s.billingSources.map(sourceLabel).join('、')||'暂无请求'}。上方今日额度仍为整个账号的总量。`;
      }
      const list=$('attempts');list.replaceChildren();
      for(const a of d.attempts){
        const card=text('article','','connection');
        card.append(text('h3',a.model+' · '+sourceLabel(a.billingSource)));
        const actual=a.actualTokens===null||a.actualTokens===undefined?'未知':number(a.actualTokens);
        const price=a.pricingStatus==='KNOWN'&&a.costAmount!==null?a.costAmount+' '+a.currency:'价格未知';
        card.append(text('p',`${a.purpose} · ${a.provider}\n实际 Token：${actual} · 输入估算：${number(a.reservedTokens)}\n用量状态：${a.usageStatus} · 请求结果：${a.outcome} · ${price}\n运行：${a.runId}\n${a.createdAt}`,'details'));
        list.append(card);
      }
      if(!d.attempts.length)list.append(text('p','还没有模型请求记录。','muted'));
      $('message').textContent='';
    } catch(error){$('message').textContent=error.message||'用量加载失败';$('message').className='error';}
    finally{$('refresh').disabled=false;}
  }
  $('refresh').addEventListener('click',refresh);refresh();
})();
