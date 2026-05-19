#!/usr/bin/env node

const serverInfo = { name: "local-writing-mcp", version: "1.0.0" };
let lineBuffer = "";

process.stdin.on("data", (chunk) => {
  lineBuffer += chunk.toString("utf8");
  parseMessages();
});

function parseMessages() {
  while (true) {
    const lineEnd = lineBuffer.indexOf("\n");
    if (lineEnd < 0) return;

    const line = lineBuffer.slice(0, lineEnd).trim();
    lineBuffer = lineBuffer.slice(lineEnd + 1);
    if (!line) continue;
    try {
      handleMessage(JSON.parse(line));
    } catch (error) {
      respond(null, null, {
        code: -32700,
        message: `Parse error: ${error.message}`,
      });
    }
  }
}

async function handleMessage(message) {
  if (message.method === "notifications/initialized") {
    return;
  }

  if (message.method === "initialize") {
    respond(message.id, {
      protocolVersion: "2024-11-05",
      capabilities: { tools: { listChanged: false } },
      serverInfo,
    });
    return;
  }

  if (message.method === "tools/list") {
    respond(message.id, { tools: tools() });
    return;
  }

  if (message.method === "tools/call") {
    const { name, arguments: args = {} } = message.params || {};
    try {
      const text = await callTool(name, args);
      respond(message.id, { content: [{ type: "text", text }] });
    } catch (error) {
      respond(message.id, null, { code: -32603, message: error.message });
    }
    return;
  }

  if (message.id !== undefined) {
    respond(message.id, null, { code: -32601, message: `Unknown method: ${message.method}` });
  }
}

function tools() {
  return [
    {
      name: "lt_check_text",
      description: "Check spelling, grammar, and style issues in text. Uses the public LanguageTool API when available.",
      inputSchema: {
        type: "object",
        properties: {
          text: { type: "string", description: "Text to check." },
          language: { type: "string", description: "Language code, for example auto, en-US, zh-CN, de-DE.", default: "auto" },
        },
        required: ["text"],
      },
    },
    {
      name: "lt_check_text_summary",
      description: "Return a compact issue summary for spelling, grammar, and style checks.",
      inputSchema: {
        type: "object",
        properties: {
          text: { type: "string", description: "Text to check." },
          language: { type: "string", description: "Language code.", default: "auto" },
        },
        required: ["text"],
      },
    },
    {
      name: "lt_list_languages",
      description: "List common LanguageTool language codes supported by this local MCP.",
      inputSchema: {
        type: "object",
        properties: {},
      },
    },
  ];
}

async function callTool(name, args) {
  if (name === "lt_list_languages") {
    return [
      "auto",
      "en-US",
      "en-GB",
      "zh-CN",
      "de-DE",
      "fr-FR",
      "es",
      "pt-BR",
      "it",
      "nl",
    ].join("\n");
  }

  if (name === "lt_check_text" || name === "lt_check_text_summary") {
    const text = String(args.text || "").trim();
    if (!text) throw new Error("text is required");
    const language = String(args.language || "auto").trim() || "auto";
    const report = await checkText(text, language);
    return name === "lt_check_text_summary" ? summarize(report) : formatReport(report);
  }

  throw new Error(`Unknown tool: ${name}`);
}

async function checkText(text, language) {
  try {
    const params = new URLSearchParams();
    params.set("text", text.slice(0, 20000));
    params.set("language", language);

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 12000);
    const response = await fetch("https://api.languagetool.org/v2/check", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: params,
      signal: controller.signal,
    });
    clearTimeout(timeout);

    if (!response.ok) {
      throw new Error(`LanguageTool public API returned ${response.status}`);
    }
    const data = await response.json();
    return { source: "LanguageTool public API", matches: data.matches || [], fallback: false };
  } catch (error) {
    return { source: `basic fallback (${error.message})`, matches: basicChecks(text), fallback: true };
  }
}

function basicChecks(text) {
  const checks = [];
  const patterns = [
    { re: /\bteh\b/gi, msg: "Possible spelling mistake: 'teh'.", repl: "the" },
    { re: /\ban\s+test\b/gi, msg: "Use 'a test' instead of 'an test'.", repl: "a test" },
    { re: /\bThis are\b/g, msg: "Possible agreement issue: 'This are'.", repl: "This is" },
    { re: /\s{2,}/g, msg: "Multiple consecutive spaces.", repl: " " },
  ];

  for (const pattern of patterns) {
    for (const match of text.matchAll(pattern.re)) {
      checks.push({
        message: pattern.msg,
        offset: match.index || 0,
        length: match[0].length,
        replacements: [{ value: pattern.repl }],
        rule: { id: "LOCAL_BASIC", category: { name: "Basic writing checks" } },
        context: { text: snippet(text, match.index || 0, match[0].length) },
      });
    }
  }
  return checks;
}

function formatReport(report) {
  const matches = report.matches || [];
  if (matches.length === 0) {
    return `No writing issues found.\nSource: ${report.source}`;
  }
  return [
    `Found ${matches.length} issue(s).`,
    `Source: ${report.source}`,
    "",
    ...matches.slice(0, 30).map((m, i) => {
      const replacements = (m.replacements || []).slice(0, 5).map((r) => r.value).join(", ");
      const context = m.context && m.context.text ? `\nContext: ${m.context.text}` : "";
      return `${i + 1}. ${m.message}${replacements ? `\nSuggestions: ${replacements}` : ""}${context}`;
    }),
  ].join("\n");
}

function summarize(report) {
  const matches = report.matches || [];
  if (matches.length === 0) return `No writing issues found. Source: ${report.source}`;
  const categories = new Map();
  for (const match of matches) {
    const category = match.rule && match.rule.category && match.rule.category.name
      ? match.rule.category.name
      : "Other";
    categories.set(category, (categories.get(category) || 0) + 1);
  }
  const detail = [...categories.entries()].map(([key, value]) => `${key}: ${value}`).join("; ");
  return `Found ${matches.length} issue(s). ${detail}. Source: ${report.source}`;
}

function snippet(text, offset, length) {
  const start = Math.max(0, offset - 30);
  const end = Math.min(text.length, offset + length + 30);
  return text.slice(start, end);
}

function respond(id, result, error) {
  const message = error
    ? { jsonrpc: "2.0", id, error }
    : { jsonrpc: "2.0", id, result };
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

process.stderr.write("local-writing-mcp running on stdio\n");
