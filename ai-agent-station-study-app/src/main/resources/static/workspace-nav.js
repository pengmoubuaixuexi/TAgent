// One navigation catalog for every signed-in workspace page. API authorization remains server-side.
(() => {
  'use strict';
  const groups = [
    {label: '工作台', links: [
      ['agents', '我的 Agent', '/user-agents.html'], ['models', '模型连接', '/models.html'],
      ['knowledge', '我的知识库', '/knowledge.html'], ['memory', '长期记忆', '/memory.html']
    ]},
    {label: '运行与产物', links: [
      ['history', '运行历史', '/history.html'], ['outcomes', '产物与反馈', '/outcomes.html'],
      ['eval', '我的评测', '/eval.html']
    ]},
    {label: '观测', links: [
      ['usage', '用量与额度', '/usage.html'], ['observe', 'LLM 观测', '/observe.html'],
      ['mcp', 'MCP 连接与工具', '/observe-mcp.html']
    ]},
    {label: '管理', admin: true, links: [
      ['stats', '网站统计', '/admin-stats.html'], ['config', '配置详情', '/agent-config.html']
    ]}
  ];
  let mount, user, revision = 0, unread = 0;
  const pathname = location.pathname === '/' ? '/index.html' : location.pathname;
  function element(tag, text, className) {
    const node = document.createElement(tag);
    if (text != null) node.textContent = text;
    if (className) node.className = className;
    return node;
  }
  function link([key, label, href]) {
    const node = element('a', label, 'workspace-nav-link');
    node.href = href; node.dataset.navKey = key;
    if (pathname === href) node.setAttribute('aria-current', 'page');
    return node;
  }
  function closeMenus(except) {
    mount?.querySelectorAll('details[open]').forEach(node => { if (node !== except) node.open = false; });
  }
  function render() {
    if (!mount) return;
    mount.replaceChildren();
    mount.hidden = !user;
    if (!user) return;
    const nav = element('nav'); nav.id = 'workspace-nav'; nav.setAttribute('aria-label', '工作台与观测导航');
    const brand = element('a', 'TAgent', 'workspace-nav-brand'); brand.href = '/index.html'; nav.append(brand);
    nav.append(link(['chat', '对话', '/index.html']));
    const menus = element('div', null, 'workspace-nav-menus');
    for (const group of groups) {
      if (group.admin && user.role !== 'ADMIN') continue;
      const details = element('details', null, 'workspace-nav-group');
      const current = group.links.find(item => item[2] === pathname);
      const summary = element('summary', group.label);
      if (current) summary.append(element('span', ' · ' + current[1], 'workspace-nav-current'));
      if (current) details.dataset.active = 'true';
      const list = element('ul', null, 'workspace-nav-popup'); list.setAttribute('aria-label', group.label);
      for (const item of group.links) { const row = element('li'); row.append(link(item)); list.append(row); }
      details.append(summary, list); details.addEventListener('toggle', () => { if (details.open) closeMenus(details); });
      menus.append(details);
    }
    nav.append(menus);
    const actions = element('div', null, 'workspace-nav-actions');
    const inbox = link(['inbox', '收件箱', '/index.html?inbox=1']);
    inbox.addEventListener('click', event => {
      if (typeof window.TAgentInbox?.open === 'function') { event.preventDefault(); event.stopPropagation(); closeMenus(); window.TAgentInbox.open(); }
    });
    const count = element('span', null, 'workspace-nav-badge'); count.hidden = true; count.dataset.navUnread = '';
    inbox.append(count); actions.append(inbox, link(['account', '账户', '/account.html']));
    nav.append(actions); mount.append(nav); setUnread(unread);
    window.dispatchEvent(new CustomEvent('workspace-nav-ready'));
  }
  function setUser(value) {
    revision++;
    // Never trust cached localStorage roles; callers supply the authenticated /auth/me or login result.
    user = value && typeof value === 'object' ? value : null;
    render();
  }
  async function refresh() {
    const request = ++revision;
    try {
      const response = await fetch('/api/v1/auth/me', {credentials: 'same-origin', cache: 'no-store', headers: {Accept: 'application/json'}});
      const payload = response.ok ? await response.json() : null;
      if (request !== revision) return;
      user = payload?.code === '0000' && payload.data ? payload.data : null;
      render();
    } catch (_) {
      if (request === revision) { user = null; render(); }
    }
  }
  function setUnread(value) {
    const total = Number(value), count = mount?.querySelector('[data-nav-unread]');
    const normalized = Number.isFinite(total) ? Math.max(0, Math.floor(total)) : 0;
    unread = normalized;
    if (!count) return;
    count.hidden = normalized === 0; count.textContent = normalized > 99 ? '99+' : String(normalized);
    count.parentElement.setAttribute('aria-label', normalized ? '收件箱，' + normalized + ' 条未读' : '收件箱');
  }
  function initialize() {
    mount = document.querySelector('[data-workspace-nav]');
    if (!mount) return;
    mount.hidden = true;
    const size = () => document.documentElement.style.setProperty('--workspace-nav-height', mount.getBoundingClientRect().height + 'px');
    if (typeof ResizeObserver === 'function') new ResizeObserver(size).observe(mount);
    size();
    document.addEventListener('click', event => { if (!mount.contains(event.target)) closeMenus(); });
    document.addEventListener('keydown', event => {
      if (event.key !== 'Escape') return;
      const open = mount.querySelector('details[open]');
      if (open) { closeMenus(); open.querySelector('summary')?.focus(); }
    });
    window.addEventListener('tagent:inbox-count', event => setUnread(event.detail?.unread));
    if (!document.querySelector('link[href="/workspace-inbox.css"]')) {
      const style = document.createElement('link'); style.rel = 'stylesheet'; style.href = '/workspace-inbox.css'; document.head.append(style);
    }
    if (!document.querySelector('script[src="/workspace-inbox.js"]')) {
      const script = document.createElement('script'); script.src = '/workspace-inbox.js'; script.defer = true; document.head.append(script);
    }
    if (user) render(); else refresh();
  }
  window.WorkspaceNav = Object.freeze({refresh, setUser, setUnread});
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, {once: true});
  else initialize();
})();
