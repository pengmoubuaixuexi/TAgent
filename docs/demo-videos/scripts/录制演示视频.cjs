const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const BASE_URL = process.env.TAGENT_DEMO_URL || 'http://localhost:8099';
const OUT_DIR = path.resolve(__dirname, '..');
const VIEWPORT = {
  width: Number(process.env.TAGENT_DEMO_WIDTH || 1280),
  height: Number(process.env.TAGENT_DEMO_HEIGHT || 720)
};
const RECORD_SIZE = { ...VIEWPORT };
const SHOW_OVERLAY = process.env.TAGENT_DEMO_OVERLAY === '1';
const ADMIN_USER = process.env.TAGENT_DEMO_USER || 'admin';
const ADMIN_PASS = process.env.TAGENT_DEMO_PASSWORD || '123456';
const REDO_SOURCE_SESSION_ID = process.env.TAGENT_DEMO_REDO_SESSION_ID;
const REDO_COMMAND = process.env.TAGENT_DEMO_REDO_COMMAND;

fs.mkdirSync(OUT_DIR, { recursive: true });

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

async function overlay(page, title, subtitle = '') {
  if (!SHOW_OVERLAY) return;
  await page.evaluate(({ title, subtitle }) => {
    let el = document.getElementById('__tagent_demo_overlay');
    if (!el) {
      el = document.createElement('div');
      el.id = '__tagent_demo_overlay';
      el.style.cssText = [
        'position:fixed',
        'left:24px',
        'top:18px',
        'z-index:999999',
        'background:rgba(2,6,23,.88)',
        'border:1px solid rgba(34,211,238,.55)',
        'box-shadow:0 12px 32px rgba(0,0,0,.35)',
        'border-radius:10px',
        'padding:12px 16px',
        'max-width:620px',
        'font-family:Inter,system-ui,Segoe UI,Arial,sans-serif',
        'color:#e5faff',
        'pointer-events:none'
      ].join(';');
      document.body.appendChild(el);
    }
    el.innerHTML = `
      <div style="font-size:17px;font-weight:700;letter-spacing:0">${title}</div>
      ${subtitle ? `<div style="font-size:12px;color:#a5f3fc;margin-top:4px;line-height:1.45">${subtitle}</div>` : ''}
    `;
  }, { title, subtitle });
}

async function hideOverlay(page) {
  if (!SHOW_OVERLAY) return;
  await page.evaluate(() => document.getElementById('__tagent_demo_overlay')?.remove());
}

async function login(page) {
  await page.goto(BASE_URL + '/', { waitUntil: 'domcontentloaded' });
  const loggedIn = await page.locator('#agentSelect').isVisible().catch(() => false);
  if (!loggedIn) {
    await page.locator('#loginUsername').fill(ADMIN_USER);
    await page.locator('#loginPassword').fill(ADMIN_PASS);
    await page.locator('#loginBtn').click();
  }
  await page.waitForSelector('#agentSelect', { timeout: 15000 });
  await page.waitForTimeout(600);
}

async function selectAgent(page, agentId) {
  await page.locator('#agentSelect').selectOption(agentId == null ? '' : String(agentId));
  await page.waitForTimeout(500);
}

async function setPlanReview(page, enabled) {
  const checked = await page.locator('#planReviewToggle').isChecked();
  if (checked !== enabled) {
    await page.locator('#planReviewToggle').setChecked(enabled);
  }
}

async function setMaxStep(page, value) {
  await page.locator('#maxStepInput').fill(String(value));
  await page.locator('#maxStepInput').evaluate(el => {
    el.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

async function newSession(page) {
  const btn = page.getByRole('button', { name: '+ 新会话' });
  if (await btn.count()) {
    await btn.click();
    await page.waitForTimeout(700);
  }
}

async function send(page, message, options = {}) {
  await page.locator('#msgInput').fill(message);
  await page.waitForTimeout(options.beforeClickMs || 500);
  await page.locator('#sendBtn').click();
}

async function waitIdle(page, timeoutMs = 180000) {
  try {
    await page.waitForFunction(() => {
      const btn = document.getElementById('sendBtn');
      const text = btn ? (btn.textContent || '').trim() : '';
      const buttonIdle = btn && !btn.disabled && text.includes('发送');
      const finalVisible = !!document.querySelector('.final-answer .msg-copy-final:not(.hidden), .msg-copy-final:not(.hidden)');
      return buttonIdle || finalVisible || (window.state && !window.state.sending);
    }, null, { timeout: timeoutMs });
  } catch (e) {
    const btnText = await page.locator('#sendBtn').textContent().catch(() => '');
    if (btnText && btnText.includes('取消')) {
      await page.locator('#sendBtn').click().catch(() => {});
      await page.waitForTimeout(2500);
    }
    return false;
  }
  await page.waitForTimeout(1200);
  return true;
}

async function waitCurrentRunIdle(page, timeoutMs = 240000) {
  await page.waitForFunction(() => window.state && window.state.sending === true, null, { timeout: 10000 }).catch(() => {});
  await page.waitForFunction(() => !window.state || window.state.sending === false, null, { timeout: timeoutMs }).catch(() => {});
  await page.waitForTimeout(1500);
}

async function waitVisible(page, selector, timeoutMs = 90000) {
  await page.waitForSelector(selector, { state: 'visible', timeout: timeoutMs });
  await page.waitForTimeout(800);
}

async function waitAny(page, selectors, timeoutMs = 120000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    for (const selector of selectors) {
      if (await page.locator(selector).first().isVisible().catch(() => false)) {
        await page.waitForTimeout(500);
        return selector;
      }
    }
    await page.waitForTimeout(500);
  }
  throw new Error(`Timed out waiting for any selector: ${selectors.join(', ')}`);
}

async function scrollMessages(page, delta = 500) {
  await page.evaluate((delta) => {
    const area = document.getElementById('messageArea');
    if (area) area.scrollTop += delta;
  }, delta);
  await page.waitForTimeout(500);
}

async function scenarioBasicStrategies(page) {
  await login(page);
  await overlay(page, '基础问答：Fixed / Auto / Flow', '同一聊天页切换三种执行策略，观察 step 卡片与最终回复。');

  await selectAgent(page, '8011');
  await send(page, '用两句话说明一下 TAgent 是什么。');
  await waitIdle(page);

  await selectAgent(page, '8012');
  await send(page, '帮我给 Python 初学者列一个 3 步学习建议。');
  await waitIdle(page);

  await selectAgent(page, '8013');
  await setPlanReview(page, false);
  await send(page, '帮我规划一个周末学习 Spring AI 的小计划。');
  await waitIdle(page);
  await scrollMessages(page, -1200);
  await sleep(1500);
  await scrollMessages(page, 1800);
}

async function scenarioAutoRouting(page) {
  await login(page);
  await overlay(page, '自动路由', '不手动选择 Agent，由后端根据用户请求选择最匹配的 Agent。');
  await selectAgent(page, null);
  await send(page, '我最近想系统学习 Python，但不知道从哪里开始，能给我一些建议吗？');
  await waitAny(page, ['text=路由到', 'text=学习路径规划师', '.step-card'], 90000).catch(() => {});
  await waitIdle(page);
}

async function scenarioRuntimeRequestTool(page) {
  await login(page);
  await overlay(page, '执行期 request_tool 动态补挂工具', 'Flow 在分析到缺少外部能力时，通过 request_tool 临时装载可用工具。');
  await selectAgent(page, '8013');
  await setPlanReview(page, false);
  await send(page, '帮我规划一下杭州到拉萨的三日游，需要查询交通、天气和景点信息。');
  await waitAny(page, ['text=request_tool', '.tool-card'], 160000);
  await sleep(3500);
  await waitIdle(page, 240000).catch(() => {});
}

async function scenarioAskUser(page) {
  await login(page);
  await overlay(page, 'ask_user 主动询问', '需求信息不足时，Agent 主动弹出用户补充信息窗口。');
  await selectAgent(page, '8013');
  await setPlanReview(page, false);
  await setMaxStep(page, 5);
  await send(page, '帮我规划一下去西藏三天游。');
  await waitVisible(page, '#userInputModal', 160000);
  await page.locator('#userInputAnswer').fill('从杭州出发，三日游，预算 5000 元，一个人出行，偏好自然风景和轻松节奏。');
  await sleep(1500);
  await page.locator('#userInputSubmitBtn').click();
  await sleep(1500);
  await waitIdle(page, 240000).catch(() => {});
}

async function scenarioFlowPlanReview(page) {
  await login(page);
  await overlay(page, 'Flow 计划确认 / 编辑 / 执行', 'Step2 生成执行计划后暂停，用户可以编辑计划，再确认继续执行。');
  await selectAgent(page, '8013');
  await setPlanReview(page, true);
  await send(page, '帮我规划一个西藏三日游。');
  await waitAny(page, ['#planReviewModal:not(.hidden)', '[data-plan-review-card="true"]'], 180000);
  if (!(await page.locator('#planReviewModal').isVisible())) {
    await page.locator('[data-plan-review-card="true"]').click();
    await waitVisible(page, '#planReviewModal', 10000);
  }
  await sleep(1000);
  const rows = page.locator('#planReviewSteps .plan-review-row');
  const count = await rows.count();
  if (count > 0) {
    const last = rows.nth(count - 1);
    const content = last.locator('[data-field="content"]');
    await content.fill((await content.inputValue()) + '\n最后请祝我生日快乐。');
  }
  await sleep(1500);
  await page.locator('#planReviewConfirmBtn').click();
  await sleep(1200);
  await waitIdle(page, 300000).catch(() => {});
}

async function scenarioAutoIntervention(page) {
  await login(page);
  await overlay(page, 'Auto 引导与立即回答', '运行中输入补充想法进行 steer，随后用 answer_now 基于已有结果收尾。');
  await selectAgent(page, '8012');
  await setMaxStep(page, 8);
  await send(page, '帮我制定一个比较详细的 Java JVM 调优学习计划。');
  await waitVisible(page, '#interveneBar', 90000);
  await page.locator('#msgInput').fill('请更偏向面试准备和线上排障实战。');
  await sleep(1000);
  await page.locator('#steerBtn').click();
  await sleep(4500);
  if (await page.locator('#answerNowBtn').isEnabled().catch(() => false)) {
    await page.locator('#answerNowBtn').click();
  }
  await waitIdle(page, 240000).catch(() => {});
}

async function scenarioObservability(page) {
  await login(page);
  await overlay(page, 'Token 消耗与 MCP 观测', '先切换最近 30 天查看 Token，再进入 MCP 工具治理页面。');
  await page.goto(BASE_URL + '/observe.html', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#range', { timeout: 10000 });
  await page.locator('#range').selectOption('720');
  await page.waitForTimeout(1200);
  await overlay(page, '最近 30 天 Token 消耗', '按模型、按会话查看累计调用与 token 使用情况。');
  await page.waitForTimeout(4500);
  await page.goto(BASE_URL + '/observe-mcp.html', { waitUntil: 'domcontentloaded' });
  await overlay(page, 'MCP 工具治理观测', '经过前面多轮演示后，工具调用与治理记录会更丰富。');
  await page.waitForTimeout(4500);
}

async function scenarioMemoryRagEvidence(page) {
  await login(page);
  await overlay(page, 'Memory 与 RAG 依据', '回答下方展示记忆依据和知识库依据，方便追溯答案来源。');
  await selectAgent(page, '8008');
  await send(page, '请记住：我现在主要学习 Python，并且更喜欢用项目实战来学习。');
  await waitIdle(page, 180000).catch(() => {});
  await send(page, '结合你记住的内容，给我一份学习建议，并展示可以参考的依据。');
  await waitAny(page, ['.memory-evidence-container .evidence-card', '.rag-evidence-container .evidence-card', 'text=记忆依据', 'text=RAG'], 180000).catch(() => {});
  await waitIdle(page, 240000).catch(() => {});
}

async function scenarioHumanApproval(page) {
  await login(page);
  await overlay(page, '高危工具人工审批', '高风险工具调用会被拦截，前端弹出审批窗口，由用户批准或拒绝。');
  await selectAgent(page, '8011');
  await send(page, '请立即调用 web_search 工具搜索「TAgent 人工审批演示」，然后简要说明搜索结果。');
  await waitVisible(page, '#approvalModal', 180000);
  await sleep(2000);
  await page.locator('#approvalApproveBtn').click();
  await sleep(1500);
  await waitIdle(page, 180000).catch(() => {});
}

async function scenarioRunIdRedo(page) {
  await login(page);
  await overlay(page, 'RunId 步骤级重做', '回到历史 Flow 会话，用 /runId-stepN 从指定步骤重新执行。');
  await selectAgent(page, '8013');
  if (!REDO_SOURCE_SESSION_ID || !REDO_COMMAND) {
    throw new Error('重做演示需要设置 TAGENT_DEMO_REDO_SESSION_ID 和 TAGENT_DEMO_REDO_COMMAND');
  }
  const sourceSessionId = REDO_SOURCE_SESSION_ID;
  await page.evaluate(async ({ sourceSessionId }) => {
    const userId = (window.state && window.state.userId) || '10001';
    if (typeof window.loadConversation === 'function') {
      await window.loadConversation(`default:${userId}:${sourceSessionId}`, { keepHistoryPanelHidden: true });
    } else if (window.state) {
      window.state.sessionId = sourceSessionId;
      const info = document.getElementById('sessionInfo');
      if (info) info.textContent = `Session: ${sourceSessionId.substring(0, 28)}...`;
    }
  }, { sourceSessionId });
  await sleep(1500);
  await overlay(page, 'RunId 步骤级重做', '从指定步骤带着新的修正要求重新执行。');
  await send(page, REDO_COMMAND);
  await waitAny(page, ['text=继承历史运行', 'text=高原反应药物', 'text=准备高原'], 120000).catch(() => {});
  await waitCurrentRunIdle(page, 300000);
}

const scenarios = [
  ['01-三种策略基础问答.webm', scenarioBasicStrategies],
  ['02-自动路由选择智能体.webm', scenarioAutoRouting],
  ['03-执行期动态补挂工具.webm', scenarioRuntimeRequestTool],
  ['04-主动追问.webm', scenarioAskUser],
  ['05-流程计划确认编辑.webm', scenarioFlowPlanReview],
  ['06-自动模式引导立即回答.webm', scenarioAutoIntervention],
  ['07-记忆与RAG依据.webm', scenarioMemoryRagEvidence],
  ['08-高危工具人工审批.webm', scenarioHumanApproval],
  ['09-运行编号步骤重做.webm', scenarioRunIdRedo],
  ['10-令牌消耗与MCP观测.webm', scenarioObservability],
];

async function recordOne(name, scenario) {
  const tempDir = path.join(OUT_DIR, '.tmp-recordings');
  fs.mkdirSync(tempDir, { recursive: true });
  const browser = await chromium.launch({
    channel: process.env.TAGENT_DEMO_BROWSER_CHANNEL || 'chrome',
    headless: false,
    args: [`--window-size=${VIEWPORT.width},${VIEWPORT.height}`]
  });
  const context = await browser.newContext({
    viewport: VIEWPORT,
    recordVideo: { dir: tempDir, size: RECORD_SIZE }
  });
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  page.setDefaultNavigationTimeout(30000);
  let error = null;
  try {
    await scenario(page);
    await hideOverlay(page).catch(() => {});
    await page.waitForTimeout(1000);
  } catch (e) {
    error = e;
    await overlay(page, '录制未完整触发', e.message || String(e)).catch(() => {});
    await page.screenshot({ path: path.join(OUT_DIR, name.replace(/\.webm$/, '.failure.png')), fullPage: false }).catch(() => {});
    await page.waitForTimeout(2500);
  }
  const target = path.join(OUT_DIR, name);
  const video = page.video();
  const saveVideo = video ? video.saveAs(target) : Promise.resolve();
  await page.close().catch(() => {});
  await context.close().catch(() => {});
  await browser.close().catch(() => {});
  await saveVideo;
  if (error) throw error;
  return target;
}

async function main() {
  const onlyArg = process.argv.find(x => x.startsWith('--only='));
  const only = onlyArg ? onlyArg.slice('--only='.length).split(',').map(x => x.trim()).filter(Boolean) : null;
  const selected = only
    ? scenarios.filter(([name]) => only.some(item => name.startsWith(item) || name.includes(item)))
    : scenarios;
  if (!selected.length) throw new Error('未匹配到任何录制场景，请检查 --only 参数');
  for (const [name, scenario] of selected) {
    console.log(`正在录制 ${name} ...`);
    const target = await recordOne(name, scenario);
    console.log(`已保存 ${target}`);
  }
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
