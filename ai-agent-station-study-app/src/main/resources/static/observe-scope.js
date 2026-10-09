// The server independently authorizes every scope; this helper only manages the view.
window.ObserveScope = (() => {
    let ready = false;
    let administrator = false;
    let version = 0;
    function current() {
        return administrator && document.getElementById('observation-scope')?.value === 'all' ? 'all' : 'mine';
    }
    async function initialize({adminOnly = false} = {}) {
        const response = await fetch('/api/v1/auth/me', {cache: 'no-store', headers: {Accept: 'application/json'}});
        if (response.status === 401) {
            location.replace('/index.html');
            throw new Error('请先登录');
        }
        if (!response.ok) throw new Error('无法校验登录状态，请重试');
        const payload = await response.json();
        if (payload.code !== '0000' || !payload.data) throw new Error('无法校验登录状态，请重试');
        administrator = payload.data.role === 'ADMIN';
        if (adminOnly && !administrator) throw new Error('网站访问与注册统计仅管理员可见');
        document.querySelectorAll('[data-admin-only]').forEach(element => element.hidden = !administrator);
        const control = document.getElementById('scope-control');
        if (control) control.hidden = !administrator;
        document.getElementById('observation-scope')?.addEventListener('change', () => {
            version++;
            const note = document.getElementById('scope-note');
            if (note) note.textContent = current() === 'all'
                ? '管理员全站视图：包含各账户与系统的数据。'
                : '本人视图：仅展示归属于当前账户的数据。';
            window.dispatchEvent(new Event('observation-scope-change'));
            if (typeof window.refresh === 'function') window.refresh();
        });
        ready = true;
        return payload.data;
    }
    function url(input) {
        const url = new URL(input, location.origin);
        url.searchParams.set('scope', current());
        return url.pathname + url.search;
    }
    return {initialize, url, get ready() { return ready; }, get version() { return version; }};
})();
