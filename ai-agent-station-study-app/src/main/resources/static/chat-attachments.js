// Chat attachments: one entry for files, images and the user's own artifacts.
// Files upload right away and are parsed on the server; a message can be sent once every file is ready.
(() => {
  'use strict';
  const FILES = '/api/v1/agent/files', ARTIFACTS = '/api/v1/workspace/outcomes/artifacts', encode = encodeURIComponent;
  const MAX_FILES = 10, MAX_BYTES = 50 * 1024 * 1024;
  const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp']);
  const SUPPORTED = new Set(('txt md markdown log text csv tsv xlsx xlsm xls ods pdf docx doc rtf odt pptx ppt odp json jsonl yaml yml xml '
    + 'html htm toml ini conf cfg properties env tex rst srt vtt py java js mjs cjs ts jsx tsx css scss sass less sql sh bash zsh ps1 bat '
    + 'cmd c h cc cpp cxx hpp cs go rs rb php kt kts swift scala r jl lua pl dart vue svelte gradle groovy proto graphql gql tf hcl ex exs '
    + 'erl hs fs clj zig asm m ipynb').split(' '));
  const SUPPORTED_NAMES = new Set(['dockerfile', 'makefile', 'readme', 'license']);
  const ACCEPT = [...SUPPORTED].map(e => '.' + e).join(',') + ',image/*';
  const KIND_LABELS = { TEXT: '文本', CODE: '代码', MARKDOWN: 'Markdown 文档', CSV: 'CSV 表格', SHEET: 'Excel 表格',
    PDF: 'PDF 文档', DOCUMENT: 'Word 文档', SLIDES: '演示文稿' };
  const ICONS = { TEXT: '📄', CODE: '💻', MARKDOWN: '📝', CSV: '📊', SHEET: '📊', PDF: '📕', DOCUMENT: '📘', SLIDES: '📙' };
  const FORMAT_KINDS = { MARKDOWN: 'MARKDOWN', TABLE: 'SHEET', CODE: 'CODE', FILE: 'FILE' };
  const items = [];
  let seq = 0;

  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = String(text); return n; };
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const ext = name => { const i = String(name || '').lastIndexOf('.'); return i < 0 ? '' : String(name).slice(i + 1).toLowerCase(); };

  function kindLabel(fileKind) { return KIND_LABELS[String(fileKind || '').toUpperCase()] || '文件'; }

  function sizeLabel(bytes) {
    const n = Number(bytes);
    if (!Number.isFinite(n) || n <= 0) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
  }

  function guessKind(name) {
    const e = ext(name);
    if (e === 'md' || e === 'markdown') return 'MARKDOWN';
    if (e === 'csv' || e === 'tsv') return 'CSV';
    if (['xlsx', 'xlsm', 'xls', 'ods'].includes(e)) return 'SHEET';
    if (e === 'pdf') return 'PDF';
    if (['docx', 'doc', 'rtf', 'odt'].includes(e)) return 'DOCUMENT';
    if (['pptx', 'ppt', 'odp'].includes(e)) return 'SLIDES';
    if (['txt', 'log', 'text', 'json', 'jsonl', 'yaml', 'yml', 'xml', 'html', 'htm'].includes(e)) return 'TEXT';
    return 'CODE';
  }

  // ---------------------------------------------------------------- composer

  function select(fileList, onImages) {
    const images = [];
    for (const file of Array.from(fileList || [])) {
      const e = ext(file.name);
      if ((file.type && file.type.startsWith('image/')) || IMAGE_EXT.has(e)) { images.push(file); continue; }
      if (!SUPPORTED.has(e) && !SUPPORTED_NAMES.has(String(file.name).toLowerCase())) {
        alert(`暂不支持「${file.name}」。可以上传文本、Markdown、代码、CSV、Excel、PDF、Word、PPT 和图片。`);
        continue;
      }
      if (file.size > MAX_BYTES) { alert(`「${file.name}」超过 50 MB`); continue; }
      if (file.size === 0) { alert(`「${file.name}」是空文件`); continue; }
      if (items.length >= MAX_FILES) { alert(`每条消息最多添加 ${MAX_FILES} 个附件`); break; }
      const item = { id: ++seq, kind: 'UPLOAD', name: file.name, size: file.size, fileKind: guessKind(file.name), status: 'uploading' };
      items.push(item);
      void upload(item, file);
    }
    render();
    if (images.length && typeof onImages === 'function') onImages(images);
  }

  async function upload(item, file) {
    const form = new FormData();
    form.append('file', file);
    try {
      const res = await fetch(FILES, { method: 'POST', body: form });
      const body = await res.json().catch(() => ({}));
      if (!res.ok || body.code !== '0000') throw new Error(body.info || `上传失败（HTTP ${res.status}）`);
      apply(item, body.data);
      if (item.status === 'parsing') void poll(item);
    } catch (e) {
      item.status = 'failed';
      item.error = e.message || '上传失败';
    }
    render();
  }

  function apply(item, data) {
    if (!data) return;
    item.attachmentId = data.attachmentId;
    item.fileKind = data.fileKind || item.fileKind;
    item.typeLabel = data.typeLabel;
    item.summary = data.summary;
    item.truncated = !!data.truncated;
    item.status = data.parseStatus === 'READY' ? 'ready' : data.parseStatus === 'FAILED' ? 'failed' : 'parsing';
    item.error = data.parseError || (item.status === 'failed' ? '无法读取这个文件' : '');
  }

  async function poll(item) {
    const started = Date.now();
    while (items.includes(item) && item.status === 'parsing' && Date.now() - started < 5 * 60 * 1000) {
      await sleep(1500);
      if (!items.includes(item)) return;
      try {
        const res = await fetch(FILES + '/' + encode(item.attachmentId), { cache: 'no-store' });
        const body = await res.json().catch(() => ({}));
        if (res.ok && body.code === '0000') apply(item, body.data);
      } catch (_) { /* keep polling */ }
      render();
    }
    if (items.includes(item) && item.status === 'parsing') {
      item.status = 'failed';
      item.error = '解析时间过长，请移除后重新上传';
      render();
    }
  }

  function addArtifact(a) {
    if (!a || !a.artifactId) return false;
    const revision = Number(a.revision || a.currentRevision || 0) || null;
    const existing = items.find(i => i.kind === 'ARTIFACT' && i.artifactId === a.artifactId);
    if (existing) { existing.revision = revision; render(); return true; }
    if (items.length >= MAX_FILES) { alert(`每条消息最多添加 ${MAX_FILES} 个附件`); return false; }
    const fileKind = FORMAT_KINDS[a.format] || 'MARKDOWN';
    items.push({ id: ++seq, kind: 'ARTIFACT', artifactId: a.artifactId, revision, name: a.title || '我的产物', fileKind,
      format: a.format, status: 'ready', typeLabel: '我的产物·' + kindLabel(fileKind) + (revision ? ` 第 ${revision} 版` : '') });
    render();
    return true;
  }

  function remove(id) {
    const index = items.findIndex(i => i.id === id);
    if (index < 0) return;
    const [item] = items.splice(index, 1);
    if (item.kind === 'UPLOAD' && item.attachmentId) {
      fetch(FILES + '/' + encode(item.attachmentId), { method: 'DELETE' }).catch(() => {});
    }
    render();
  }

  function statusText(item) {
    if (item.status === 'uploading') return '上传中…';
    if (item.status === 'parsing') return '解析中…';
    if (item.status === 'failed') return item.error || '处理失败';
    const parts = [item.typeLabel || kindLabel(item.fileKind)];
    if (item.kind === 'UPLOAD') parts.push(sizeLabel(item.size));
    if (item.summary && item.kind === 'UPLOAD') parts.push(item.summary);
    return parts.filter(Boolean).join(' · ');
  }

  function render() {
    const strip = document.getElementById('fileAttachmentStrip');
    if (!strip) return;
    strip.replaceChildren();
    strip.classList.toggle('hidden', items.length === 0);
    for (const item of items) {
      const chip = el('div', 'chat-attach-chip');
      chip.dataset.status = item.status;
      chip.title = item.status === 'failed' ? (item.error || '') : (item.summary || item.name);
      chip.append(el('span', 'chat-attach-icon', item.status === 'uploading' || item.status === 'parsing' ? '⏳' : ICONS[item.fileKind] || '📎'));
      const text = el('div', 'chat-attach-text');
      text.append(el('div', 'chat-attach-name', item.name), el('div', 'chat-attach-meta', statusText(item)));
      const close = el('button', 'chat-attach-remove', '×');
      close.type = 'button';
      close.title = '移除';
      close.onclick = () => remove(item.id);
      chip.append(text, close);
      strip.append(chip);
    }
    document.dispatchEvent(new CustomEvent('chat-attachments-changed'));
  }

  /** Why the message cannot be sent yet, or null. */
  function blocking() {
    if (items.some(i => i.status === 'uploading')) return '附件还在上传，请稍候再发送';
    if (items.some(i => i.status === 'parsing')) return '附件还在解析，请稍候再发送';
    if (items.some(i => i.status === 'failed')) return '有附件处理失败，请先移除它（鼠标移到附件上可以看到原因）';
    return null;
  }

  function payload() {
    return items.map(i => i.kind === 'ARTIFACT' ? { artifactId: i.artifactId, revision: i.revision } : { attachmentId: i.attachmentId });
  }

  /** Cards shown in the user's bubble right after sending (same shape as history files). */
  function cards() {
    return items.map(i => ({ kind: i.kind, attachmentId: i.attachmentId || null, fileName: i.name, fileKind: i.fileKind,
      size: i.size || null, artifactId: i.artifactId || null, artifactRevision: i.revision || null, status: 'ACTIVE' }));
  }

  function count() { return items.length; }

  function clear() { items.length = 0; render(); }

  /** Session switch: unsent uploads are removed from the server as well. */
  function discard() {
    for (const item of items) {
      if (item.kind === 'UPLOAD' && item.attachmentId) fetch(FILES + '/' + encode(item.attachmentId), { method: 'DELETE' }).catch(() => {});
    }
    clear();
  }

  // ---------------------------------------------------------------- menu and picker

  let menu;
  function openMenu(anchor, chooseFiles) {
    closeMenu();
    menu = el('div', 'chat-attach-menu');
    const upload = el('button', '', '📎 上传文件或图片');
    upload.type = 'button';
    upload.onclick = () => { closeMenu(); chooseFiles(); };
    const artifact = el('button', '', '🗂️ 引用我的产物');
    artifact.type = 'button';
    artifact.onclick = () => { closeMenu(); void openPicker(); };
    const hint = el('div', 'chat-attach-hint', '文档、表格、PDF、代码等单个最大 50 MB，每条消息最多 10 个；也可以把文件拖进输入框。');
    menu.append(upload, artifact, hint);
    document.body.append(menu);
    const rect = anchor.getBoundingClientRect();
    menu.style.left = Math.max(8, rect.left) + 'px';
    menu.style.bottom = (window.innerHeight - rect.top + 8) + 'px';
    setTimeout(() => document.addEventListener('click', outside, { once: true }), 0);
  }
  function outside(event) { if (menu && !menu.contains(event.target)) closeMenu(); else if (menu) document.addEventListener('click', outside, { once: true }); }
  function closeMenu() { if (menu) { menu.remove(); menu = null; } }

  async function openPicker() {
    const overlay = el('div', 'chat-attach-overlay');
    const dialog = el('div', 'chat-attach-dialog');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-label', '引用我的产物');
    const head = el('div', 'chat-attach-dialog-head');
    head.append(el('h3', '', '引用我的产物'));
    const closeBtn = el('button', '', '×');
    closeBtn.type = 'button';
    closeBtn.onclick = () => overlay.remove();
    head.append(closeBtn);
    const search = el('input');
    search.placeholder = '按名称筛选';
    const list = el('div', 'chat-attach-list', '正在读取…');
    const foot = el('p', 'chat-attach-hint', 'Agent 会读取所选的版本；让它修改时，会生成这个产物的新版本，原版本保留。只能引用自己的产物。');
    dialog.append(head, search, list, foot);
    overlay.append(dialog);
    overlay.onclick = e => { if (e.target === overlay) overlay.remove(); };
    document.body.append(overlay);
    search.focus();
    let rows = [];
    try {
      const res = await fetch(ARTIFACTS, { cache: 'no-store' });
      const body = await res.json().catch(() => ({}));
      if (!res.ok || body.code !== '0000') throw new Error(body.info || '产物列表暂时无法读取');
      rows = Array.isArray(body.data) ? body.data : [];
    } catch (e) {
      list.textContent = e.message;
      return;
    }
    const draw = () => {
      const q = search.value.trim().toLowerCase();
      const shown = rows.filter(a => !q || String(a.title || '').toLowerCase().includes(q));
      list.replaceChildren();
      if (!shown.length) { list.append(el('p', 'chat-attach-hint', rows.length ? '没有匹配的产物' : '还没有保存过产物')); return; }
      for (const a of shown) {
        const row = el('button', 'chat-attach-row');
        row.type = 'button';
        const kind = FORMAT_KINDS[a.format] || 'MARKDOWN';
        row.append(el('span', 'chat-attach-icon', ICONS[kind]),
          el('span', 'chat-attach-row-title', a.title || '未命名产物'),
          el('span', 'chat-attach-row-meta', `${kindLabel(kind)} · 第 ${a.currentRevision} 版 · ${String(a.updatedAt || '').slice(0, 16)}`));
        row.onclick = () => { if (addArtifact(a)) overlay.remove(); };
        list.append(row);
      }
    };
    search.oninput = draw;
    draw();
  }

  /** /index.html?attachArtifact=<id>&revision=<n> from the outcomes page. */
  async function initFromUrl() {
    const params = new URLSearchParams(location.search);
    const id = params.get('attachArtifact');
    if (!id) return;
    params.delete('attachArtifact');
    const revision = Number(params.get('revision') || 0);
    params.delete('revision');
    history.replaceState(null, '', location.pathname + (params.toString() ? '?' + params : '') + location.hash);
    try {
      const res = await fetch(ARTIFACTS + '/' + encode(id) + '?revision=' + (Number.isSafeInteger(revision) && revision > 0 ? revision : 0), { cache: 'no-store' });
      const body = await res.json().catch(() => ({}));
      if (!res.ok || body.code !== '0000') throw new Error(body.info || '产物不存在或无权引用');
      addArtifact({ artifactId: body.data.artifactId, revision: body.data.revision, title: body.data.title, format: body.data.format });
      document.getElementById('msgInput')?.focus();
    } catch (e) {
      alert('无法引用这个产物：' + e.message);
    }
  }

  function bindDrop(target, onImages) {
    if (!target) return;
    let depth = 0;
    target.addEventListener('dragenter', e => { if (hasFiles(e)) { e.preventDefault(); depth++; target.classList.add('chat-attach-drop'); } });
    target.addEventListener('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
    target.addEventListener('dragleave', () => { if (--depth <= 0) { depth = 0; target.classList.remove('chat-attach-drop'); } });
    target.addEventListener('drop', e => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      depth = 0;
      target.classList.remove('chat-attach-drop');
      select(e.dataTransfer.files, onImages);
    });
  }
  const hasFiles = e => Array.from(e.dataTransfer?.types || []).includes('Files');

  // ---------------------------------------------------------------- messages

  /** Attachment cards in a user message (live or restored from history). */
  function renderCards(files) {
    if (!Array.isArray(files) || !files.length) return '';
    const cardsHtml = files.map(f => {
      const purged = f.status === 'PURGED';
      const artifact = f.kind === 'ARTIFACT';
      const kind = String(f.fileKind || '').toUpperCase();
      const label = artifact ? `我的产物·${kindLabel(kind)}${f.artifactRevision ? ` 第 ${f.artifactRevision} 版` : ''}` : kindLabel(kind);
      const meta = purged ? '附件已清理' : [label, artifact ? '' : sizeLabel(f.size)].filter(Boolean).join(' · ');
      let href = '';
      if (!purged && artifact && f.artifactId) {
        href = '/outcomes.html?artifactId=' + encode(f.artifactId) + (f.artifactRevision ? '&revision=' + encode(f.artifactRevision) : '');
      } else if (!purged && f.attachmentId) {
        href = FILES + '/' + encode(f.attachmentId) + '/download';
      }
      const inner = `<span class="chat-attach-icon">${purged ? '🗑️' : ICONS[kind] || '📎'}</span>`
        + `<span class="chat-attach-text"><span class="chat-attach-name">${esc(f.fileName || '附件')}</span>`
        + `<span class="chat-attach-meta">${esc(meta)}</span></span>`;
      return href
        ? `<a class="chat-attach-card" href="${esc(href)}" ${artifact ? 'target="_blank" rel="noopener"' : 'download'} title="${artifact ? '查看产物' : '下载原文件'}">${inner}</a>`
        : `<span class="chat-attach-card" data-status="${purged ? 'PURGED' : ''}">${inner}</span>`;
    }).join('');
    return `<div class="chat-attach-cards">${cardsHtml}</div>`;
  }

  /** Run event attachment_notice, e.g. the model cannot see images. Shown once per code. */
  function notice(aiMsg, data) {
    if (!aiMsg || !data || !data.message) return;
    const key = String(data.code || data.message);
    let box = aiMsg.querySelector('.attachment-notes');
    if (!box) {
      const content = aiMsg.querySelector('.msg-content');
      if (!content) return;
      box = el('div', 'attachment-notes');
      content.parentElement.insertBefore(box, content);
    }
    if ([...box.children].some(n => n.dataset.key === key)) return;
    const line = el('div', '', 'ⓘ ' + data.message);
    line.dataset.key = key;
    box.append(line);
  }

  window.ChatAttachments = { ACCEPT, select, addArtifact, remove, blocking, payload, cards, count, clear, discard,
    openMenu, openPicker, initFromUrl, bindDrop, renderCards, notice, render };
})();
