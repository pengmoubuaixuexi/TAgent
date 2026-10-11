(() => {
  'use strict';
  const base = '/api/v1/workspace/model-connections';
  const $ = id => document.getElementById(id);
  let rotating;
  function message(text, error = false) { $('message').textContent = text; $('message').className = error ? 'error' : ''; }
  async function api(path = '', method = 'GET', body) {
    const response = await fetch(base + path, {method, headers: {'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
    const result = await response.json();
    if (!response.ok || result.code !== '0000') throw new Error(result.info || '操作失败');
    return result.data;
  }
  async function perform(button, action) {
    button.disabled = true;
    try { await action(); } catch (e) { message(e.message || '操作失败', true); }
    finally { button.disabled = false; }
  }
  function element(tag, text, cls) { const node = document.createElement(tag); if (text) node.textContent = text; if (cls) node.className = cls; return node; }
  function action(label, fn, cls) { const button = element('button', label, cls); button.type = 'button'; button.addEventListener('click', () => perform(button, fn)); return button; }
  async function refresh() {
    const data = await api();
    $('platformAuxiliaries').checked = data.allowPlatformAuxiliaries;
    $('availability').hidden = data.secretsAvailable;
    $('availability').textContent = data.secretsAvailable ? '' : '管理员尚未配置密钥加密服务，暂不能添加或轮换个人连接。平台模型仍可使用。';
    $('createConnection').disabled = !data.secretsAvailable;
    $('allowedHosts').textContent = '允许的提供商域名：' + (data.allowedHosts || []).join('、') + '。其他兼容服务需管理员先配置域名。';
    const list = $('connections'); list.replaceChildren();
    if (!data.connections.length) list.append(element('p', '还没有个人连接。保存后，可在“我的 Agent”的模型列表中选择。', 'muted'));
    for (const connection of data.connections) {
      const card = element('article', '', 'connection'); card.append(element('h3', connection.name));
      const status = Number(connection.status) === 1 ? '已启用' : '已撤销';
      card.append(element('p', `${connection.modelName} · ${status} · 密钥版本 ${connection.credentialVersion}\n${connection.baseUrl}\n费用来源：个人提供商账号 · 密钥：${connection.maskedKey}\n${connection.verifiedAt ? '连接验证成功' : '尚未验证当前密钥'}`, 'details'));
      const actions = element('div', '', 'actions');
      actions.append(action('验证连接（不生成内容）', async () => { await api('/' + encodeURIComponent(connection.apiId) + '/verify', 'POST'); message('连接与模型名称验证成功。'); await refresh(); }, 'secondary'));
      const rotate = action('轮换密钥', async () => { rotating = connection.apiId; $('newKey').value = ''; $('rotateDialog').showModal(); });
      rotate.disabled = !data.secretsAvailable; actions.append(rotate);
      if (Number(connection.status) === 1) actions.append(action('撤销连接', async () => { await api('/' + encodeURIComponent(connection.apiId), 'DELETE'); message('连接已撤销，后续请求会被拒绝。可通过轮换密钥重新启用。'); await refresh(); }, 'danger'));
      card.append(actions); list.append(card);
    }
  }
  $('connectionForm').addEventListener('submit', event => {
    event.preventDefault();
    const form = event.currentTarget;
    const body = Object.fromEntries(new FormData(form)); body.allowPlatformAuxiliaries = $('createAuxiliaries').checked;
    form.elements.apiKey.value = '';
    perform($('createConnection'), async () => { await api('', 'POST', body); message('个人连接已加密保存，可验证后前往“我的 Agent”选择。'); form.reset(); await refresh(); });
  });
  $('savePolicy').addEventListener('click', () => perform($('savePolicy'), async () => { await api('/auxiliary-policy', 'PUT', {allowPlatformAuxiliaries: $('platformAuxiliaries').checked}); message('平台辅助服务设置已保存。'); }));
  $('refresh').addEventListener('click', () => perform($('refresh'), refresh));
  $('cancelRotate').addEventListener('click', () => { $('newKey').value = ''; $('rotateDialog').close(); });
  $('rotateDialog').addEventListener('close', () => { $('newKey').value = ''; rotating = undefined; });
  $('rotateForm').addEventListener('submit', event => {
    event.preventDefault(); const body = {apiKey: $('newKey').value}; const id = rotating; $('newKey').value = '';
    perform(event.submitter, async () => { await api('/' + encodeURIComponent(id) + '/rotate', 'POST', body); $('rotateDialog').close(); message('密钥已轮换，后续请求立即使用新密钥。'); await refresh(); });
  });
  refresh().catch(e => message(e.message || '模型连接加载失败', true));
})();
