// Shared same-origin session/CSRF transport for all application pages.
(() => {
  const originalFetch = window.fetch.bind(window);
  let csrf;
  async function refreshCsrf() {
    const response = await originalFetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) throw new Error('无法建立登录校验，请刷新页面');
    return response.json();
  }
  window.fetch = async (input, options = {}) => {
    const url = new URL(input instanceof Request ? input.url : input, location.href);
    if (url.origin !== location.origin) return originalFetch(input, options);
    const method = (options.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
    const headers = new Headers(options.headers || (input instanceof Request ? input.headers : undefined));
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      const token = await (csrf ||= refreshCsrf().catch(e => { csrf = null; throw e; }));
      headers.set(token.headerName, token.token);
    }
    const response = await originalFetch(input, { ...options, credentials: 'same-origin', headers });
    if (url.pathname === '/api/v1/auth/login' || url.pathname === '/api/v1/auth/logout') csrf = null;
    if (response.status === 401 && !url.pathname.startsWith('/api/v1/auth/')) {
      localStorage.removeItem('agent_login');
      location.replace('/index.html');
    }
    return response;
  };
})();
