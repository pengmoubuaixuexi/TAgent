// One owner-scoped inbox on every workspace page. Chat supplies its existing interaction-aware adapter.
(() => {
  'use strict';
  if (window.TAgentInbox) return;
  const api = '/api/v1/agent/notifications';
  let items = [], userId = null, panel, previousFocus, loading = false, filter = 'all', markingAll = false, refreshVersion = 0;
  const pending = item => ['WAITING', 'PLAN_REVIEW', 'PENDING'].includes(String(item.status || ''))
    && (!item.expiresAt || Number(item.expiresAt) > Date.now());
  const unread = item => String(item.readStatus || 'UNREAD') === 'UNREAD';
  const counts = () => window.TAgentChatInbox ? window.TAgentChatInbox.counts()
    : {unread:items.filter(unread).length, pending:items.filter(pending).length};
  function broadcast() { window.dispatchEvent(new CustomEvent('tagent:inbox-count', {detail:counts()})); }
  async function json(url, method = 'GET') {
    const response = await fetch(url, {method, cache:'no-store', headers:{Accept:'application/json'}});
    const result = await response.json();
    if (!response.ok || result.code !== '0000') {
      const error = new Error(result.info || '通知暂时无法加载'); error.status = response.status; throw error;
    }
    return result.data;
  }
  async function refresh(afterMarkAll = false) {
    if (window.TAgentChatInbox) return window.TAgentChatInbox.refresh();
    if (markingAll && !afterMarkAll || loading && !afterMarkAll) return;
    const version = ++refreshVersion;
    loading = true;
    try {
      const user = await json('/api/v1/auth/me');
      if (version !== refreshVersion) return;
      const owner = String(user?.userId || '');
      if (!owner) { items = []; userId = null; render(); broadcast(); return; }
      if (owner !== userId) items = [];
      userId = owner;
      const rows = await json(api + '?limit=100&includeArchived=false');
      if (owner !== userId || version !== refreshVersion) return;
      items = Array.isArray(rows) ? rows : [];
      render(); broadcast(); modelAlerts.show(items);
    } catch (error) {
      if (version !== refreshVersion) return;
      if (error.status === 401 || error.status === 403) { items = []; userId = null; render(); broadcast(); }
      if (panel && !panel.hidden) panel.querySelector('[data-inbox-message]').textContent = error.message;
    } finally { if (version === refreshVersion) loading = false; }
  }
  async function markAllRead() {
    if (window.TAgentChatInbox) return window.TAgentChatInbox.markAllRead();
    if (markingAll || !userId || !items.some(unread)) return;
    const owner = userId;
    const before = Math.max(0, ...items.map(item => Number(item.updatedAt || item.createdAt || 0)));
    if (!before) return;
    markingAll = true; ++refreshVersion; loading = false; render();
    try {
      await json(api + '/ack-all?before=' + encodeURIComponent(before), 'POST');
      if (owner === userId) await refresh(true);
    } catch (error) {
      if (owner === userId && panel) panel.querySelector('[data-inbox-message]').textContent = '一键已读失败：' + error.message;
    } finally {
      markingAll = false;
      if (panel) updateMarkAllButton();
    }
  }
  function updateMarkAllButton() {
    const button = panel.querySelector('[data-inbox-mark-all]');
    button.disabled = markingAll || !items.some(unread);
    button.textContent = markingAll ? '正在标为已读…' : '一键已读';
  }
  function el(tag, text, className) {
    const value = document.createElement(tag);
    if (text) value.textContent = text;
    if (className) value.className = className;
    return value;
  }
  function close() { if (panel) panel.hidden = true; if (previousFocus?.isConnected) previousFocus.focus(); }
  function ensurePanel() {
    if (panel) return;
    panel = el('section', '', 'workspace-inbox'); panel.id = 'workspaceInbox'; panel.hidden = true;
    panel.setAttribute('role', 'dialog'); panel.setAttribute('aria-modal', 'false'); panel.setAttribute('aria-label', '收件箱');
    const header = el('header'), title = el('strong', '收件箱'), closeButton = el('button', '关闭');
    closeButton.type = 'button'; closeButton.onclick = close; header.append(title, closeButton);
    const tabs = el('div', '', 'workspace-inbox-tabs');
    for (const [key, name] of [['all', '全部'], ['pending', '待处理']]) {
      const button = el('button', name); button.type = 'button'; button.dataset.filter = key;
      button.onclick = () => { filter = key; render(); }; tabs.append(button);
    }
    const update = el('button', '刷新'); update.type = 'button'; update.onclick = () => refresh(); tabs.append(update);
    const markAll = el('button', '一键已读'); markAll.type = 'button'; markAll.dataset.inboxMarkAll = 'true';
    markAll.title = '将当前通知标为已读，待处理事项仍需单独处理'; markAll.onclick = markAllRead; tabs.append(markAll);
    const message = el('p', '正在加载…', 'workspace-inbox-summary'); message.dataset.inboxMessage = 'true';
    const list = el('div', '', 'workspace-inbox-list'); list.dataset.inboxList = 'true';
    panel.append(header, tabs, message, list); document.body.append(panel);
    panel.addEventListener('keydown', event => { if (event.key === 'Escape') close(); });
  }
  function label(item) {
    if (item.type === 'MODEL_RECOVERED' || item.type === 'MODEL_UNAVAILABLE' && item.status === 'RESOLVED') return '模型已恢复';
    if (item.type === 'MODEL_UNAVAILABLE') return '模型不可用';
    if (item.type === 'TASK_COMPLETED') return '任务已完成';
    if (item.type === 'TASK_FAILED') return '任务未完成';
    if (pending(item)) return item.type === 'PLAN_REVIEW' ? '待确认计划' : item.type === 'TOOL_APPROVAL' ? '待审批' : '待回答';
    if (item.status === 'EXPIRED' || item.expiresAt && Number(item.expiresAt) <= Date.now()) return '已过期';
    return '已处理';
  }
  function render() {
    if (!panel) return;
    const current = counts();
    updateMarkAllButton();
    panel.querySelector('[data-inbox-message]').textContent = `${current.unread} 条未读 · ${current.pending} 条待处理`;
    panel.querySelectorAll('[data-filter]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.filter === filter)));
    const list = panel.querySelector('[data-inbox-list]'); list.replaceChildren();
    const visible = items.filter(item => filter === 'all' || pending(item));
    if (!visible.length) { list.append(el('p', filter === 'all' ? '暂时没有通知' : '暂时没有待处理事项')); return; }
    for (const item of visible) {
      const card = el('article', '', 'workspace-inbox-item' + (unread(item) ? ' unread' : ''));
      card.dataset.notificationId = item.notificationId;
      const link = el('a'); link.href = modelLink(item) || '/index.html?notificationId=' + encodeURIComponent(item.notificationId);
      link.append(el('strong', item.title || 'Agent 通知'), el('p', item.summary || ''), el('small', label(item)));
      // Navigating alone is not an acknowledgement: chat marks read only after opening the owned target.
      card.append(link);
      if (unread(item)) {
        const acknowledge = el('button', '标为已读'); acknowledge.type = 'button';
        acknowledge.onclick = async () => {
          acknowledge.disabled = true;
          try { await json(api + '/' + encodeURIComponent(item.notificationId) + '/ack', 'POST'); await refresh(); }
          catch (error) { panel.querySelector('[data-inbox-message]').textContent = error.message; acknowledge.disabled = false; }
        };
        card.append(acknowledge);
      }
      list.append(card);
    }
  }
  // Model health links point to the model pages; only same-origin paths are followed.
  function modelLink(item) {
    const link = item && item.metadata && item.metadata.link;
    return ['MODEL_UNAVAILABLE', 'MODEL_RECOVERED'].includes(String(item.type || '')) && typeof link === 'string'
      && link.startsWith('/') && !link.startsWith('//') ? link : null;
  }
  // A card pops up once per outage (metadata.popupKey); repeated failures only update the inbox entry.
  const modelAlerts = (() => {
    const storageKey = 'tagent_model_alerts_shown';
    let host;
    function shown() { try { return new Set(JSON.parse(localStorage.getItem(storageKey) || '[]')); } catch (_) { return new Set(); } }
    function remember(keys) { try { localStorage.setItem(storageKey, JSON.stringify([...keys].slice(-200))); } catch (_) { /* private mode */ } }
    function card(item) {
      if (!host) { host = el('div', '', 'model-alerts'); host.setAttribute('role', 'status'); host.setAttribute('aria-live', 'polite'); document.body.append(host); }
      const recovered = item.type === 'MODEL_RECOVERED';
      const box = el('article', '', 'model-alert' + (recovered ? ' recovered' : ''));
      const summary = String(item.summary || '');
      box.append(el('strong', item.title || (recovered ? '模型已恢复' : '模型暂不可用')), el('p', summary.length > 220 ? summary.slice(0, 220) + '…' : summary));
      const actions = el('div', '', 'model-alert-actions');
      const link = modelLink(item);
      if (link) { const view = el('a', '查看'); view.href = link; actions.append(view); }
      const dismiss = el('button', '知道了'); dismiss.type = 'button';
      dismiss.onclick = async () => {
        box.remove();
        try { await fetch(api + '/' + encodeURIComponent(item.notificationId) + '/ack', {method:'POST', headers:{Accept:'application/json'}}); } catch (_) { /* stays unread */ }
        void refresh();
      };
      actions.append(dismiss); box.append(actions); host.append(box);
    }
    function show(list) {
      const keys = shown(); let changed = false;
      for (const item of list || []) {
        if (!['MODEL_UNAVAILABLE', 'MODEL_RECOVERED'].includes(String(item.type || ''))) continue;
        if (!unread(item) || item.status === 'RESOLVED' || Date.now() - Number(item.createdAt || 0) > 24 * 3600 * 1000) continue;
        const key = String((item.metadata && item.metadata.popupKey) || item.notificationId);
        if (keys.has(key)) continue;
        keys.add(key); changed = true; card(item);
      }
      if (changed) remember(keys);
    }
    return {show};
  })();
  window.TAgentModelAlerts = modelAlerts;
  window.TAgentInbox = {
    open() {
      if (window.TAgentChatInbox) return window.TAgentChatInbox.open();
      ensurePanel(); previousFocus = document.activeElement; panel.hidden = false;
      render(); panel.querySelector('button').focus(); return refresh();
    }, refresh, counts, markAllRead
  };
  window.addEventListener('tagent:workspace-nav-ready', broadcast);
  window.addEventListener('workspace-nav-ready', broadcast);
  window.addEventListener('storage', event => {
    if (event.key === 'agent_login') { items = []; userId = null; render(); broadcast(); void refresh(); }
  });
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') void refresh(); });
  // The chat page already owns its poll; other pages retain the same 15-second inbox freshness.
  setInterval(() => { if (!window.TAgentChatInbox && document.visibilityState === 'visible') void refresh(); }, 15000);
  void refresh();
})();
