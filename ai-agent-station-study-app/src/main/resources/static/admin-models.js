(() => {
  'use strict';
  const base = '/api/v1/admin/model-catalog';
  const $ = id => document.getElementById(id);
  function message(text, error = false) { $('message').textContent = text; $('message').className = error ? 'error' : ''; }
  async function api(path = '', method = 'GET', body) {
    const response = await fetch(base + path, {method, headers: {'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
    const result = await response.json().catch(() => ({}));
    if (!response.ok || result.code !== '0000') throw new Error(result.info || '操作失败');
    return result.data;
  }
  function element(tag, text, cls) { const node = document.createElement(tag); if (text != null) node.textContent = text; if (cls) node.className = cls; return node; }
  function button(label, cls, fn) {
    const node = element('button', label, cls); node.type = 'button';
    node.addEventListener('click', async () => {
      node.disabled = true;
      try { await fn(); } catch (e) { message(e.message || '操作失败', true); } finally { node.disabled = false; }
    });
    return node;
  }
  const time = value => value ? new Date(Number(value)).toLocaleString() : '';
  function healthCell(entry) {
    const cell = element('td'), health = entry.health || {};
    if (!entry.published) cell.append(element('span', '未开放给用户', 'badge warn'), element('small', '开放后才会作为备用'));
    const state = health.state || 'UNKNOWN';
    const badge = element('span', state === 'HEALTHY' ? '可用' : state === 'UNHEALTHY' ? '不可用' : '未出现过失败',
      'badge ' + (state === 'HEALTHY' ? 'ok' : state === 'UNHEALTHY' ? 'bad' : 'unknown'));
    cell.append(badge);
    if (health.reason) cell.append(element('small', '最近错误：' + health.reason + (health.lastFailureAt ? ' · ' + time(health.lastFailureAt) : '')));
    if (health.detail) cell.append(element('small', '服务商返回：' + health.detail));
    if (state === 'UNHEALTHY' && health.unhealthySince) cell.append(element('small', '不可用开始于 ' + time(health.unhealthySince)));
    if (health.lastProbeAt) cell.append(element('small', '最近检测 ' + time(health.lastProbeAt)));
    return cell;
  }
  function tierSelect(value) {
    const select = element('select');
    for (const tier of ['small', 'medium', 'large']) { const option = element('option', tier); option.value = tier; option.selected = tier === value; select.append(option); }
    return select;
  }
  async function refresh() {
    const data = await api();
    const body = $('catalog').querySelector('tbody'); body.replaceChildren();
    if (!data.entries.length) { const row = element('tr'); const cell = element('td', '清单为空：平台模型没有备用对象。', 'muted'); cell.colSpan = 6; row.append(cell); body.append(row); }
    for (const entry of data.entries) {
      const row = element('tr');
      const name = element('td'); name.append(element('strong', entry.modelName), element('small', (entry.host || entry.apiId) + (entry.imageInput ? ' · 可看图' : '')));
      const tier = tierSelect(entry.tier), order = element('input'); order.type = 'number'; order.min = '0'; order.max = '10000'; order.value = entry.sortOrder;
      const enabled = element('input'); enabled.type = 'checkbox'; enabled.checked = !!entry.enabled; enabled.setAttribute('aria-label', '启用');
      const tierCell = element('td'); tierCell.append(tier);
      const orderCell = element('td'); orderCell.append(order);
      const enabledCell = element('td'); enabledCell.append(enabled);
      const actions = element('div', '', 'actions');
      const key = {apiId: entry.apiId, modelName: entry.modelName};
      actions.append(
        button('保存', '', async () => { await api('', 'PUT', {...key, tier: tier.value, sortOrder: Number(order.value), enabled: enabled.checked}); message('已保存 ' + entry.modelName + '。'); await refresh(); }),
        button('立即检测', 'secondary', async () => { const result = await api('/check', 'POST', key); message(entry.modelName + '：' + (result.available ? '检测通过，已标记为可用。' : '检测未通过（' + result.reason + '）。'), !result.available); await refresh(); }),
        button('移出清单', 'danger', async () => { if (!confirm('移出后，' + entry.modelName + ' 不再作为其他平台模型的备用。确定吗？')) return; await api('?apiId=' + encodeURIComponent(entry.apiId) + '&modelName=' + encodeURIComponent(entry.modelName), 'DELETE'); message('已移出 ' + entry.modelName + '。'); await refresh(); }));
      const actionCell = element('td'); actionCell.append(actions);
      row.append(name, tierCell, orderCell, enabledCell, healthCell(entry), actionCell);
      body.append(row);
    }
    const addable = $('addable'); addable.replaceChildren();
    for (const item of data.addable) { const option = element('option', item.modelName + ' · ' + (item.host || item.apiId)); option.value = JSON.stringify({apiId: item.apiId, modelName: item.modelName}); addable.append(option); }
    $('addButton').disabled = !data.addable.length;
    if (!data.addable.length) { const option = element('option', '没有可添加的已开放平台模型'); option.value = ''; addable.append(option); }
  }
  $('addForm').addEventListener('submit', async event => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (!form.get('model')) return;
    $('addButton').disabled = true;
    try {
      await api('', 'POST', {...JSON.parse(form.get('model')), tier: form.get('tier'), sortOrder: Number(form.get('sortOrder'))});
      message('已加入清单。'); await refresh();
    } catch (e) { message(e.message || '添加失败', true); }
    finally { $('addButton').disabled = false; }
  });
  $('refresh').addEventListener('click', () => refresh().then(() => message('')).catch(e => message(e.message || '加载失败', true)));
  refresh().catch(e => message(e.message || '模型清单加载失败', true));
})();
