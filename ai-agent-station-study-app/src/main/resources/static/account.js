(() => {
  'use strict';const $=id=>document.getElementById(id);
  async function read(path){const r=await fetch(path,{cache:'no-store'});const body=await r.json();if(!r.ok||body.code!=='0000')throw new Error(body.info||'读取失败');return body.data;}
  read('/api/v1/auth/me').then(user=>{$('accountName').textContent=user.username;}).catch(e=>{$('notice').textContent=e.message;});
  read('/api/v1/workspace/model-connections').then(data=>{$('modelState').textContent=data.connections.some(c=>c.status===1)?'你已配置个人模型连接，可在节点中选择。':'目前可以先使用平台模型，个人连接按需配置。';}).catch(()=>{$('modelState').textContent='连接状态暂时无法读取，可进入模型页面重试。';});
  $('passwordForm').addEventListener('submit',async event=>{
    event.preventDefault();const current=$('currentPassword').value,next=$('newPassword').value;
    if(next!==$('confirmPassword').value){$('passwordStatus').textContent='两次输入的新密码不一致。';return;}
    if(new TextEncoder().encode(next).length>72||next.length<10){$('passwordStatus').textContent='新密码至少 10 位，且 UTF-8 长度不超过 72 字节。';return;}
    $('savePassword').disabled=true;$('passwordStatus').textContent='正在保存…';
    try{const r=await fetch('/api/v1/auth/password',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({currentPassword:current,newPassword:next})});const body=await r.json();if(!r.ok)throw new Error(body.info||'修改失败');
      $('passwordForm').reset();localStorage.removeItem('agent_login');$('passwordStatus').textContent='密码已修改。请返回对话页重新登录。';
    }catch(e){$('passwordStatus').textContent=e.message;$('currentPassword').value='';$('newPassword').value='';$('confirmPassword').value='';}finally{$('savePassword').disabled=false;}
  });
})();
