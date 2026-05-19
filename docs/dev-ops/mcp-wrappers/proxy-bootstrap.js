/**
 * Proxy Bootstrap — 让 Node 子进程的 fetch / undici 走 HTTPS_PROXY 代理。
 *
 * 用途：mcp-stdio-wrapper.js 检测到 HTTPS_PROXY env 时，会通过
 *      NODE_OPTIONS=--require=<本文件> 把这段 bootstrap 注入到 spawn 出来的 Node 子进程，
 *      子进程启动时会读取此模块并在 undici 全局设置 ProxyAgent。
 *      这样 yahoo-finance2 等内部用 fetch / undici 的库才会走代理（Node 默认不读 HTTPS_PROXY）。
 *
 * 仅作用于"由 wrapper 启动且 env 含 HTTPS_PROXY"的 MCP（当前 = 9006 finance-mcp）。
 * 国内 MCP（如 9010 豆瓣）transport_config 没设 HTTPS_PROXY → wrapper 不会注入 NODE_OPTIONS → 不受影响。
 */

const proxy = process.env.HTTPS_PROXY || process.env.HTTP_PROXY;
if (proxy) {
    try {
        const { ProxyAgent, setGlobalDispatcher } = require('undici');
        setGlobalDispatcher(new ProxyAgent(proxy));
        process.stderr.write(`[proxy-bootstrap] undici global dispatcher set to ${proxy}\n`);
    } catch (e) {
        process.stderr.write(`[proxy-bootstrap] failed to set undici proxy (${e.message}); fetch will not use proxy\n`);
    }
}
