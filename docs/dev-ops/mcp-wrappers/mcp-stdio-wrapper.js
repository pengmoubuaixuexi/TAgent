#!/usr/bin/env node
/**
 * MCP Stdio Wrapper — 解决 MCP server 将日志输出到 stdout 而非 stderr 的问题
 *
 * 原理：启动真正的 MCP server 子进程，逐行读取其 stdout，
 *       如果是合法 JSON-RPC 消息则转发到自身 stdout，否则重定向到 stderr。
 *
 * 用法：node mcp-stdio-wrapper.js <command> [args...]
 * 示例：node mcp-stdio-wrapper.js npx stockquotes-mcp --transport stdio
 */

const { spawn } = require('child_process');
const path = require('path');

const args = process.argv.slice(2);
if (args.length === 0) {
    process.stderr.write('Usage: node mcp-stdio-wrapper.js <command> [args...]\n');
    process.exit(1);
}

// 仅当 env 设置了 HTTPS_PROXY 才给子进程注入 proxy-bootstrap，让 undici 全局走代理。
// 这样 yahoo-finance2 等用 fetch 的库才会走代理（Node 默认不读 HTTPS_PROXY env）。
// 国内 MCP（如豆瓣）transport_config 没设 HTTPS_PROXY → 这里不动 NODE_OPTIONS → 不受影响。
const childEnv = { ...process.env };
if (childEnv.HTTPS_PROXY || childEnv.HTTP_PROXY) {
    const bootstrap = path.join(__dirname, 'proxy-bootstrap.js');
    const existing = childEnv.NODE_OPTIONS ? childEnv.NODE_OPTIONS + ' ' : '';
    childEnv.NODE_OPTIONS = existing + '--require ' + JSON.stringify(bootstrap);
}

const child = spawn(args[0], args.slice(1), {
    stdio: ['pipe', 'pipe', 'pipe'],
    shell: true,
    env: childEnv
});

let buffer = '';

child.stdout.on('data', (data) => {
    buffer += data.toString();
    const lines = buffer.split('\n');
    buffer = lines.pop(); // 保留不完整的行

    for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;

        // JSON-RPC 消息必须是合法 JSON，且包含 "jsonrpc" 字段
        if (isJsonRpc(trimmed)) {
            process.stdout.write(line + '\n');
        } else {
            process.stderr.write(line + '\n');
        }
    }
});

child.stderr.on('data', (data) => {
    process.stderr.write(data);
});

child.on('exit', (code) => {
    // 处理 buffer 中可能残留的最后一行
    if (buffer && buffer.trim()) {
        const trimmed = buffer.trim();
        if (isJsonRpc(trimmed)) {
            process.stdout.write(buffer);
        } else {
            process.stderr.write(buffer);
        }
    }
    process.exit(code || 0);
});

child.on('error', (err) => {
    process.stderr.write(`Wrapper error: ${err.message}\n`);
    process.exit(1);
});

// 转发 stdin 到子进程
process.stdin.pipe(child.stdin);

function isJsonRpc(line) {
    try {
        const obj = JSON.parse(line);
        // JSON-RPC 消息必须包含 "jsonrpc" 字段（请求/响应）
        // 或者是通知（method 字段但无 id）
        return typeof obj === 'object' && obj !== null && (
            obj.jsonrpc !== undefined ||
            (obj.method !== undefined && typeof obj.method === 'string')
        );
    } catch {
        return false;
    }
}
