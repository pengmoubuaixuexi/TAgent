// TAgent 2.0 architecture. Run with Node.js; no third-party generator dependency.
// Icons follow the existing architecture-overview-v5 visual vocabulary.
const fs = require('node:fs');
const path = require('node:path');
const defs = "<defs>\n  <radialGradient id=\"bgGrad\" cx=\"50%\" cy=\"38%\" r=\"78%\">\n    <stop offset=\"0%\" stop-color=\"#0b1830\"/>\n    <stop offset=\"60%\" stop-color=\"#071124\"/>\n    <stop offset=\"100%\" stop-color=\"#050b1a\"/>\n  </radialGradient>\n\n  <filter id=\"fB\" x=\"-40%\" y=\"-40%\" width=\"180%\" height=\"180%\"><feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"6\" flood-color=\"#4da3ff\" flood-opacity=\"0.55\"/></filter>\n  <filter id=\"fC\" x=\"-40%\" y=\"-40%\" width=\"180%\" height=\"180%\"><feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"6\" flood-color=\"#2fd9ec\" flood-opacity=\"0.5\"/></filter>\n  <filter id=\"fP\" x=\"-40%\" y=\"-40%\" width=\"180%\" height=\"180%\"><feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"6\" flood-color=\"#b06bff\" flood-opacity=\"0.5\"/></filter>\n  <filter id=\"fG\" x=\"-40%\" y=\"-40%\" width=\"180%\" height=\"180%\"><feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"6\" flood-color=\"#eebf5a\" flood-opacity=\"0.5\"/></filter>\n  <filter id=\"fGold2\" x=\"-40%\" y=\"-40%\" width=\"180%\" height=\"180%\"><feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"9\" flood-color=\"#ffcf6e\" flood-opacity=\"0.6\"/></filter>\n  <filter id=\"fPk\" x=\"-40%\" y=\"-40%\" width=\"180%\" height=\"180%\"><feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"6\" flood-color=\"#ff79c6\" flood-opacity=\"0.5\"/></filter>\n  <filter id=\"fGr\" x=\"-40%\" y=\"-40%\" width=\"180%\" height=\"180%\"><feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"6\" flood-color=\"#4fd48a\" flood-opacity=\"0.5\"/></filter>\n\n  <marker id=\"mW\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"6.5\" markerHeight=\"6.5\" orient=\"auto-start-reverse\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"#dfe9ff\"/></marker>\n  <marker id=\"mC\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"#35e0f2\"/></marker>\n  <marker id=\"mP\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"#c087ff\"/></marker>\n  <marker id=\"mO\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"#f7a83c\"/></marker>\n  <marker id=\"mG\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"#eebf5a\"/></marker>\n  <marker id=\"mBl\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"#7fb8ff\"/></marker>\n  <marker id=\"mPk\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"#ff8fd0\"/></marker>\n  <marker id=\"mGr\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"#5ce08f\"/></marker>\n\n  <!-- ============ icons (stroke = currentColor) ============ -->\n  <g id=\"icPerson\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3\" stroke-linecap=\"round\">\n    <circle cx=\"0\" cy=\"-9\" r=\"10\"/><path d=\"M-17,19 C-17,3 17,3 17,19\"/>\n  </g>\n  <g id=\"icMonitor\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <rect x=\"-19\" y=\"-16\" width=\"38\" height=\"26\" rx=\"3\"/><path d=\"M-4,-9 L6,-3 L-4,3 Z\"/><path d=\"M0,10 v7 M-9,17 h18\"/>\n  </g>\n  <g id=\"icRouter\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.6\">\n    <path d=\"M0,-11 L11,0 L0,11 L-11,0 Z\"/>\n    <circle cx=\"0\" cy=\"-17\" r=\"4.5\"/><circle cx=\"17\" cy=\"0\" r=\"4.5\"/><circle cx=\"0\" cy=\"17\" r=\"4.5\"/><circle cx=\"-17\" cy=\"0\" r=\"4.5\"/>\n  </g>\n  <g id=\"icBolt\" fill=\"currentColor\" stroke=\"none\">\n    <path d=\"M2,-13 L-8,2 L-1,2 L-3,13 L8,-2 L1,-2 Z\"/>\n  </g>\n  <g id=\"icLoop\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\">\n    <path d=\"M8.5,-6 A10.5,10.5 0 1 0 10.5,2\"/><path d=\"M9,-11 L9.5,-5.5 L4,-4.5\" fill=\"none\"/>\n  </g>\n  <g id=\"icFlow\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.6\" stroke-linejoin=\"round\">\n    <rect x=\"-5\" y=\"-13\" width=\"10\" height=\"8\" rx=\"2\"/><rect x=\"-15\" y=\"5\" width=\"10\" height=\"8\" rx=\"2\"/><rect x=\"5\" y=\"5\" width=\"10\" height=\"8\" rx=\"2\"/>\n    <path d=\"M0,-5 v4 M-10,5 v-2 h20 v2 M0,-1 v0\"/>\n  </g>\n  <g id=\"icClipboard\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <rect x=\"-15\" y=\"-17\" width=\"30\" height=\"38\" rx=\"4\"/><rect x=\"-7\" y=\"-21\" width=\"14\" height=\"8\" rx=\"3\"/>\n    <path d=\"M-7,2 L-2,8 L9,-5\"/>\n  </g>\n  <g id=\"icBrain\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <path d=\"M-2,-17 C-9,-17 -12,-12 -11,-8 C-16,-7 -17,0 -13,3 C-16,8 -11,14 -5,13 C-4,17 3,18 5,14 C11,16 16,11 14,6 C18,2 17,-5 12,-7 C13,-13 7,-18 2,-16 C1,-17 0,-17 -2,-17 Z\"/>\n    <path d=\"M-5,-10 C-2,-8 -2,-4 -5,-2 M4,-9 C7,-6 6,-2 3,0 M-6,5 C-3,7 1,6 2,3 M7,4 C9,6 9,9 7,10\"/>\n  </g>\n  <g id=\"icTools\" fill=\"none\" stroke=\"currentColor\" stroke-linecap=\"round\">\n    <path d=\"M-14,-14 L-6,-6\" stroke-width=\"6.5\"/>\n    <path d=\"M-6,-6 L11,11\" stroke-width=\"3\"/>\n    <path d=\"M3,-11 a7,7 0 1 1 8,8\" stroke-width=\"3\"/>\n    <path d=\"M7,-7 L-11,11\" stroke-width=\"3\"/>\n    <path d=\"M-11,11 l-3.5,3.5\" stroke-width=\"5.5\"/>\n  </g>\n  <g id=\"icCloudWifi\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\">\n    <path d=\"M-8,10 h-3 a7,7 0 0 1 -1,-14 a10,10 0 0 1 19,-3 a8,8 0 0 1 4,1\"/>\n    <path d=\"M2,6 a10,10 0 0 1 14,0 M5,11 a6,6 0 0 1 8,0\"/><circle cx=\"9\" cy=\"16\" r=\"1.8\" fill=\"currentColor\"/>\n  </g>\n  <g id=\"icPuzzle\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\">\n    <path d=\"M-13,-6 h6.5 a5.5,5.5 0 1 1 11,0 H13 v20 h-26 z\"/>\n  </g>\n  <g id=\"icDb\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\">\n    <ellipse cx=\"0\" cy=\"-11\" rx=\"13\" ry=\"5\"/><path d=\"M-13,-11 v22 a13,5 0 0 0 26,0 v-22\"/><path d=\"M-13,0 a13,5 0 0 0 26,0\"/>\n  </g>\n  <g id=\"icWrenchGear\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <path d=\"M-15,-10 A6.5,6.5 0 1 1 -8,-16 L-10,-13 L-7,-10 L-4,-12 A6.5,6.5 0 0 1 -15,-10 Z\" transform=\"rotate(42)\"/>\n    <path d=\"M-12,12 L-1,1\"/>\n    <circle cx=\"8\" cy=\"8\" r=\"5\"/><path d=\"M8,0 v3 M8,13 v3 M0,8 h3 M13,8 h3 M2.5,2.5 l2,2 M11.5,11.5 l2,2 M13.5,2.5 l-2,2 M4.5,11.5 l-2,2\"/>\n  </g>\n  <g id=\"icDocs\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\" stroke-linecap=\"round\">\n    <rect x=\"-13\" y=\"-15\" width=\"20\" height=\"26\" rx=\"3\"/><rect x=\"-7\" y=\"-10\" width=\"20\" height=\"26\" rx=\"3\"/>\n    <path d=\"M-2,-3 h10 M-2,3 h10 M-2,9 h7\"/>\n  </g>\n  <g id=\"icLayers\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\">\n    <path d=\"M0,-14 L15,-7 L0,0 L-15,-7 Z\"/><path d=\"M-15,0 L0,7 L15,0\"/><path d=\"M-15,7 L0,14 L15,7\"/>\n  </g>\n  <g id=\"icApprove\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <rect x=\"-16\" y=\"-16\" width=\"32\" height=\"32\" rx=\"7\"/><circle cx=\"0\" cy=\"-5\" r=\"5\"/><path d=\"M-9,10 C-9,1 9,1 9,10\"/>\n  </g>\n  <g id=\"icTarget\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\">\n    <circle cx=\"0\" cy=\"0\" r=\"12\"/><circle cx=\"0\" cy=\"0\" r=\"4.5\"/><path d=\"M0,-17 v5 M0,12 v5 M-17,0 h5 M12,0 h5\"/>\n  </g>\n  <g id=\"icFF\" fill=\"currentColor\" stroke=\"none\">\n    <path d=\"M-13,-9 L-1,0 L-13,9 Z\"/><path d=\"M1,-9 L13,0 L1,9 Z\"/>\n  </g>\n  <g id=\"icStop\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\" stroke-linecap=\"round\">\n    <path d=\"M-6,-14 h12 l8,8 v12 l-8,8 h-12 l-8,-8 v-12 Z\"/><path d=\"M-6,0 h12\"/>\n  </g>\n  <g id=\"icAskChat\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.6\" stroke-linejoin=\"round\">\n    <rect x=\"-15\" y=\"-14\" width=\"30\" height=\"22\" rx=\"5\"/><path d=\"M-7,8 v7 l8,-7\"/>\n    <text x=\"0\" y=\"2.5\" text-anchor=\"middle\" font-size=\"15\" font-weight=\"bold\" fill=\"currentColor\" stroke=\"none\">?</text>\n  </g>\n  <g id=\"icPersonCheck\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <circle cx=\"-4\" cy=\"-8\" r=\"8\"/><path d=\"M-16,14 C-16,2 8,2 8,14\"/><path d=\"M6,4 L11,9 L19,-1\"/>\n  </g>\n  <g id=\"icRack\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\">\n    <rect x=\"-16\" y=\"-15\" width=\"32\" height=\"30\" rx=\"3\"/><path d=\"M-16,-5 h32 M-16,5 h32\"/>\n    <circle cx=\"10\" cy=\"-10\" r=\"1.6\" fill=\"currentColor\"/><circle cx=\"10\" cy=\"0\" r=\"1.6\" fill=\"currentColor\"/><circle cx=\"10\" cy=\"10\" r=\"1.6\" fill=\"currentColor\"/>\n    <path d=\"M-11,-10 h8 M-11,0 h8 M-11,10 h8\"/>\n  </g>\n  <g id=\"icCopy\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\">\n    <rect x=\"-12\" y=\"-12\" width=\"18\" height=\"20\" rx=\"3\"/><path d=\"M-4,-16 h13 a3,3 0 0 1 3,3 v17\"/>\n  </g>\n  <g id=\"icDocLines\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\" stroke-linecap=\"round\">\n    <rect x=\"-11\" y=\"-14\" width=\"22\" height=\"28\" rx=\"3\"/><path d=\"M-5,-6 h10 M-5,0 h10 M-5,6 h6\"/>\n  </g>\n  <g id=\"icPulse\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <circle cx=\"0\" cy=\"0\" r=\"14\"/><path d=\"M-10,1 h5 l3,-7 l4,12 l3,-6 h5\"/>\n  </g>\n  <g id=\"icChart\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <circle cx=\"0\" cy=\"0\" r=\"14\"/><path d=\"M-8,5 L-3,-1 L2,3 L8,-6\"/>\n  </g>\n  <g id=\"icNet\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.6\">\n    <circle cx=\"-10\" cy=\"8\" r=\"4.5\"/><circle cx=\"1\" cy=\"-10\" r=\"4.5\"/><circle cx=\"12\" cy=\"6\" r=\"4.5\"/>\n    <path d=\"M-7,4.5 L-1.5,-6.5 M4.5,-7 L9.5,2.5 M-5.5,8.5 L7.5,6.5\"/>\n  </g>\n\n  <!-- ===== v5 新增图标 ===== -->\n  <g id=\"icImage\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\">\n    <rect x=\"-15\" y=\"-12\" width=\"30\" height=\"24\" rx=\"3\"/>\n    <circle cx=\"-6\" cy=\"-4\" r=\"3.2\"/>\n    <path d=\"M-15,8 L-4,-2 L3,5 L8,1 L15,8\"/>\n  </g>\n  <g id=\"icCloudUp\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <path d=\"M-9,7 h-2 a7,7 0 0 1 -1,-14 a10,10 0 0 1 19,-3 a7.5,7.5 0 0 1 3,14.5 h-3\"/>\n    <path d=\"M0,15 v-13 M-5,7 L0,2 L5,7\"/>\n  </g>\n  <g id=\"icEye\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\">\n    <path d=\"M-17,0 C-10,-9 10,-9 17,0 C10,9 -10,9 -17,0 Z\"/><circle cx=\"0\" cy=\"0\" r=\"4.5\"/>\n  </g>\n  <g id=\"icMemEdit\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linejoin=\"round\" stroke-linecap=\"round\">\n    <path d=\"M-13,-13 h17 M-13,-13 v26 h18 v-8\"/>\n    <path d=\"M-8,-6 h9 M-8,0 h7 M-8,6 h5\"/>\n    <path d=\"M13,-9 l5,5 l-11,11 h-5 v-5 Z\"/>\n  </g>\n  <g id=\"icShield\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\" stroke-linecap=\"round\">\n    <path d=\"M0,-16 L13,-11 v10 C13,7 6,14 0,16 C-6,14 -13,7 -13,-3 v-10 Z\"/>\n    <path d=\"M-6,-1 L-1.5,4 L7,-6\"/>\n  </g>\n  <g id=\"icReplay\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <path d=\"M-13,-1 A13,13 0 1 1 -5,11\"/>\n    <path d=\"M-18,-6 L-13,-1 L-8,-6\"/>\n    <path d=\"M-2,-6 L6,0 L-2,6 Z\" fill=\"currentColor\" stroke=\"none\"/>\n  </g>\n  <g id=\"icLease\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\" stroke-linecap=\"round\">\n    <rect x=\"-13\" y=\"-3\" width=\"26\" height=\"18\" rx=\"4\"/>\n    <path d=\"M-7,-3 v-6 a7,7 0 0 1 14,0 v6\"/>\n    <circle cx=\"0\" cy=\"6\" r=\"2.4\" fill=\"currentColor\" stroke=\"none\"/>\n  </g>\n  <g id=\"icClock\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\">\n    <circle cx=\"0\" cy=\"0\" r=\"14\"/><path d=\"M0,-8 V1 L6,5\"/>\n  </g>\n  <g id=\"icFileWatch\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.6\" stroke-linejoin=\"round\" stroke-linecap=\"round\">\n    <path d=\"M-14,-15 h13 l7,7 v13 h-20 Z\"/><path d=\"M-1,-15 v7 h7\"/>\n    <circle cx=\"8\" cy=\"10\" r=\"6.5\"/><path d=\"M12.8,14.8 L17,19\"/>\n  </g>\n  <g id=\"icHourglass\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linejoin=\"round\" stroke-linecap=\"round\">\n    <path d=\"M-10,-15 h20 M-10,15 h20\"/>\n    <path d=\"M-8,-15 v5 L0,0 L8,-10 v-5\"/><path d=\"M-8,15 v-5 L0,0 L8,10 v5\"/>\n  </g>\n  <g id=\"icReturn\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n    <path d=\"M-16,-13 h32 v18 a4,4 0 0 1 -4,4 h-19 l-9,7 Z\"/>\n    <path d=\"M8,-4 h-14 M-1,-9 L-6,-4 L-1,1\"/>\n  </g>\n  <g id=\"icNlp\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.6\" stroke-linejoin=\"round\" stroke-linecap=\"round\">\n    <rect x=\"-16\" y=\"-13\" width=\"28\" height=\"20\" rx=\"5\"/>\n    <path d=\"M-9,7 v6 l7,-6\"/><path d=\"M-9,-6 h13 M-9,0 h9\"/>\n    <path d=\"M15,-16 l1.6,4.4 l4.4,1.6 l-4.4,1.6 l-1.6,4.4 l-1.6,-4.4 l-4.4,-1.6 l4.4,-1.6 Z\" fill=\"currentColor\" stroke=\"none\"/>\n  </g>\n</defs>";
const base = path.join(__dirname, 'tagent-end-to-end-architecture-v2.0-2026-10');
const C = { blue:'#65adff', cyan:'#36d7e9', purple:'#ba8aff', amber:'#f0bd62', green:'#5dd99d', pink:'#f28fc6', white:'#edf5ff', muted:'#9fb4d1', faint:'#6684aa' };
const F = {blue:'fB',cyan:'fC',purple:'fP',amber:'fG',green:'fGr',pink:'fPk'};
const M = {blue:'mBl',cyan:'mC',purple:'mP',amber:'mG',green:'mGr',pink:'mPk',white:'mW'};
const out=[];
const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
function text(x,y,s,size=18,color=C.white,weight=400,anchor='start',maxWidth){
  out.push(`<text x="${x}" y="${y}" font-size="${size}" fill="${color}" font-weight="${weight}" text-anchor="${anchor}"${maxWidth?` data-max-width="${maxWidth}"`:''}>${esc(s)}</text>`);
}
function icon(id,x,y,k='blue',scale=.8){out.push(`<use href="#${id}" transform="translate(${x} ${y}) scale(${scale})" color="${C[k]}"/>`);}
function rect(x,y,w,h,k,fill='#0b182c',r=12,dashed=false,glow=false){out.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill}" stroke="${C[k]}" stroke-width="${dashed?1.45:1.4}"${dashed?' stroke-dasharray="8 7"':''}${glow?` filter="url(#${F[k]})"`:''}/>`);}
function line(d,k='cyan',dash=false,arrow=true,start=false){out.push(`<path d="${d}" fill="none" stroke="${C[k]}" stroke-width="${dash?1.6:2.2}" stroke-linejoin="round"${dash?' stroke-dasharray="6 5"':''}${arrow?` marker-end="url(#${M[k]})"`:''}${start?` marker-start="url(#${M[k]})"`:''}/>`);}
function group(x,y,w,h,k,title,ic){
  rect(x,y,w,h,k,'#0a1729',15,true,true);
  out.push(`<rect x="${x+1}" y="${y+1}" width="${w-2}" height="46" rx="14" fill="${C[k]}" fill-opacity=".07"/>`);
  if(ic)icon(ic,x+28,y+25,k,.65);
  text(x+(ic?51:20),y+32,title,22,C[k],700,'start',w-70);
}
function box(x,y,w,h,k,title,subtitle=[],ic){
  rect(x,y,w,h,k,'#0a172b',11,false,true);
  if(ic)icon(ic,x+w/2,y+37,k,1.05);
  const ty=y+(ic?76:29);
  text(x+w/2,ty,title,21,C.white,700,'middle',w-22);
  subtitle.forEach((s,i)=>text(x+w/2,ty+29+i*25,s,17,C.muted,400,'middle',w-22));
}
function row(x,y,w,k,title,sub,ic,h=63){
  rect(x,y,w,h,k,'#0b192c',9,false,false);
  if(ic)icon(ic,x+25,y+h/2,k,.65);
  const xx=x+(ic?49:15);
  text(xx,y+(sub?25:38),title,18,C.white,600,'start',w-(ic?64:30));
  if(sub)text(xx,y+48,sub,15.5,C.muted,400,'start',w-(ic?64:30));
}
function pill(x,y,w,label,k='blue'){rect(x,y,w,27,k,'#0c1b31',7);text(x+w/2,y+19,label,14.5,C[k],500,'middle',w-14);}
out.push(`<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="3840" height="3200" viewBox="0 0 1920 1600" role="img" aria-labelledby="title desc"><title id="title">TAgent 2.0 · 端到端架构</title><desc id="desc">基于 2026 年 10 月本地代码的架构总览。涵盖用户工作台、模型与知识、三种运行策略、MCP 工具治理、人机协作、产物、持久运行、后台任务、EvalOps、存储与观测。</desc>${defs}<style>text{font-family:'Microsoft YaHei','PingFang SC',sans-serif;letter-spacing:0} .mono{font-family:Consolas,monospace}</style>`);
out.push('<rect width="1920" height="1600" fill="url(#bgGrad)"/>');
// Restrained circuit decoration, beneath every semantic element.
out.push('<g fill="none" stroke="#153253" stroke-width="1.3" opacity=".48"><path d="M0 74H22V346H15V672H24V1062H12V1402H25V1572H220"/><path d="M1920 76H1898V349H1906V687H1895V1064H1907V1394H1898V1580H1730"/><path d="M1270 0V22H1370V45H1450"/><path d="M600 1600V1578H790V1590H900"/></g>');
for(const [x,y] of [[22,74],[15,346],[24,672],[12,1062],[25,1402],[1898,76],[1906,349],[1895,687],[1907,1064],[1898,1394]])out.push(`<circle cx="${x}" cy="${y}" r="3" fill="#1b3d63"/>`);
text(40,49,'TAgent 2.0',35,C.white,750);
text(276,49,'端到端架构',30,C.blue,700);
text(40,79,'用户工作台  ·  Agent 编排  ·  工具治理  ·  可追溯运行',17,C.muted);
pill(1630,29,250,'2026.10  ·  当前实现','blue');

// Top band: independent configuration / context sources.
group(40,110,540,217,'blue','用户工作台与版本','icLayers');
row(58,168,504,'blue','公共 Agent → 私有工作台','按模式配置：模型 / Prompt / MCP 连接 / Advisor','icPerson',64);
row(58,243,504,'blue','草稿 → 启用版本 → 运行绑定','节点调试 · 配置历史 · 用户隔离','icClipboard',64);
group(606,110,540,217,'purple','模型接入与运行治理','icBrain');
row(624,168,246,'purple','平台模型 / 个人 API','密钥加密 · 请求时鉴权',null,64);
row(882,168,246,'purple','用户额度 / 并发','Run 预算 · 上下文管理',null,64);
text(628,270,'辅助模型需单独授权 · 个人主模型不共用密钥',17,C.muted,400,'start',492);
text(628,300,'Token 预留 → 实际用量结算 · 独立记账',17,C.purple,500,'start',492);
group(1172,110,708,217,'green','知识库与四层记忆','icDocs');
text(1192,181,'知识入库：上传 → 解析分块 → 索引 → 发布',18,C.white,500,'start',668);
text(1192,216,'检索：PgVector + ES BM25 → 融合 / 重排',18,C.white,500,'start',668);
text(1192,251,'记忆：短期会话 / 工作 / 长期 / 情景',18,C.white,500,'start',668);
text(1192,295,'按用户检索 · 已发布知识可见 · 会话摘要压缩',16.5,C.green,400,'start',668);

// Orthogonal associations terminate at runtime components, not each other.
line('M310 327 V352 H573 V425','blue',true);
text(324,348,'配置版本 / 绑定范围',14.5,C.blue);
line('M876 327 V364 H1312 V425','purple',true);
text(959,356,'模型与治理',14.5,C.purple);
line('M1534 327 V349 H1422 V425','green',true);
text(1548,352,'上下文增强',14.5,C.green);
text(40,391,'运行主链',19,C.blue,700);
text(1540,391,'实线：执行 / 调用   虚线：配置 / 协作',14.5,C.muted);

// Main left-to-right backbone. The tools loop lives below Advisor + LLM.
box(40,426,130,174,'blue','用户请求',['文本 / 图片'],'icPerson');
box(204,426,200,174,'blue','安全入口',['身份 · 归属 · 幂等','先建立 SSE / ACK'],'icMonitor');
box(438,426,270,174,'purple','Dispatch / 路由与装配',['异步准备 · 可选 Agent 路由','绑定配置版本 · Armory'],'icRouter');
group(742,386,440,259,'purple','Fixed · Auto · Flow','icFlow');
row(760,441,404,'purple','Fixed：配置 Client 链顺序执行',null,'icBolt',48);
row(760,500,404,'purple','Auto：分析 → 执行 ⇄ 质检 → 汇总',null,'icLoop',48);
row(760,559,404,'purple','Flow：工具分析 → 规划 → DAG','代码解析依赖 · 可选审阅 · 就绪即调度','icFlow',66);
box(1216,426,275,174,'purple','Advisor + LLM',['上下文 · 记忆 · RAG','模型推理 ↔ 工具调用'],'icBrain');
box(1525,426,355,174,'blue','运行事件 / SSE',['RunEventPublisher → 前端','正文 · 工具 · 步骤状态 · 阶段进度'],'icCloudWifi');
for(const [a,b] of [[170,204],[404,438],[708,742],[1182,1216],[1491,1525]])line(`M${a+3} 513 H${b-6}`,'white');

// Budget is a cross-cutting scope; never an extra execution stage.
line('M438 654 V674 H1491 V654','amber',false,false);
out.push('<rect x="779" y="660" width="372" height="28" rx="7" fill="#0b1527"/>');
text(965,679,'运行预算 · 取消信号 · 上下文保护',17,C.amber,500,'middle');
line('M279 727 V649 H963 V647','amber',true);
text(296,704,'交互 / 审阅 / 干预',14.5,C.amber);
// Separate down/up directions make the model-tool result loop explicit.
line('M1285 601 V722','cyan');
line('M1338 722 V601','cyan');
text(1360,704,'调用 / 结果',14.5,C.cyan);
line('M1492 558 H1509 V704 H1628 V722','pink',true);

group(40,727,478,303,'amber','人机协作','icAskChat');
row(58,785,442,'amber','ask_user：用户补充信息','绑定当前 user + run → 后续节点共享','icAskChat',66);
row(58,865,442,'amber','Flow 计划确认 / 编辑','高风险工具审批 · 明确的等待与反馈','icClipboard',66);
text(59,971,'steer 引导 · answer_now 收束 · cancel 取消',16,C.amber,500,'start',440);
text(59,1006,'补充信息进入运行上下文，不是全局长期记忆',15.5,C.muted,400,'start',440);

group(546,727,826,303,'cyan','动态工具与 MCP 治理','icTools');
row(564,785,169,'cyan','request_tool','缺失能力请求',null,63);
row(762,785,193,'cyan','授权工具目录','受 MCP 绑定约束',null,63);
row(984,785,370,'cyan','相关性筛选 → 按需装配','精确名称 / 语义匹配 · 不凑数装配',null,63);
line('M737 816 H755','cyan');line('M959 816 H977','cyan');
row(564,865,260,'cyan','Robust ToolManager','阶段权限 · 预算 · 审批',null,63);
row(853,865,293,'cyan','MCP Registry / 调用租约','容量 · 探活重连 · 空闲回收',null,63);
row(1175,865,179,'cyan','外部 MCP','授权连接调用',null,63);
line('M828 896 H846','cyan');line('M1150 896 H1168','cyan');
text(566,966,'工具结果压缩 + resultRef → inspect_tool_result 按需回读',17,C.cyan,600,'start',784);
text(566,1006,'分析 / 规划看能力目录 · 同 MCP 串行 / 跨 MCP 并行',16,C.muted,400,'start',784);

group(1400,727,480,303,'pink','产物与反馈','icDocs');
row(1418,785,444,'pink','create_artifact','内置工具生成 · 与外部 MCP 分开','icDocLines',66);
row(1418,865,444,'pink','Markdown / 表格 CSV / 代码文件','不可变版本 · 预览 · 下载 · 用户反馈','icCopy',66);
text(1420,974,'代码产物仅保存，不执行',18,C.pink,600,'start',434);
text(1420,1007,'反馈可选择转为评测草稿，人工审核后发布',15.5,C.muted,400,'start',434);

// Support panel connectors use perimeter paths with no card crossings.
line('M40 1192 H30 V408 H462 V425','green',true);
text(44,1071,'后台触发 → 复用执行链',14.5,C.green);
line('M1702 601 H1890 V1056 H997 V1095','blue',true);
text(1009,1080,'运行事件 / 检查点',14.5,C.blue);
line('M1639 1032 V1095','pink',true);
text(1653,1074,'人工发布用例',14.5,C.pink);

group(40,1100,478,208,'green','后台任务与收件箱','icClipboard');
row(58,1156,442,'green','定时一次 / CRON / 文件变化','触发任务 → 复用 Agent 执行链','icLoop',63);
text(58,1254,'收件箱：待确认 / 待补充 / 完成 / 失败',16.5,C.white,500,'start',442);
text(58,1288,'跨页面通知 · 全部已读不等于批准',15.5,C.green,400,'start',442);

group(546,1100,826,208,'cyan','运行回放与持久历史','icLayers');
row(564,1156,250,'cyan','Redis Stream','事件游标 · 重连去重',null,63);
row(834,1156,250,'cyan','Redis Snapshot','步骤快照 · 重做上下文',null,63);
row(1104,1156,250,'cyan','MySQL History','状态 · 回答 · 有界诊断',null,63);
text(566,1254,'断线不取消 · 步骤重做创建新 Run · 失败 / 取消也可查',16,C.white,500,'start',784);
text(566,1288,'阶段进度不入持久事件 · 进程中断不自动重放外部操作',15.5,C.cyan,400,'start',784);

group(1400,1100,480,208,'blue','EvalOps 评测闭环','icTarget');
row(1418,1156,444,'blue','反馈草稿 → 发布用例 → 同链执行','规则评分 / LLM Judge / Trace','icChart',63);
text(1420,1254,'代码版本绑定 · 历史对比 · 评测会话回看',16.5,C.white,500,'start',438);
text(1420,1288,'用例反馈改进系统，不自动训练模型',15.5,C.blue,400,'start',438);

// Independent stores are a foundation, not a pipeline.
text(40,1342,'数据与文件',19,C.blue,700);
text(1650,1342,'各模块按职责读写',15,C.muted);
row(40,1360,440,'blue','MySQL','配置 · 版本 · 历史 · 额度 · 产物','icDb',73);
row(506,1360,440,'cyan','Redis','会话 · 事件 · 快照 · 工具原文','icDb',73);
row(972,1360,442,'green','PgVector + Elasticsearch','向量检索 · BM25 · 工具目录','icDb',73);
row(1440,1360,440,'pink','文件存储','知识原件 · 多模态附件','icDocs',73);

group(40,1460,1840,96,'blue','观测与运维','icPulse');
row(295,1480,400,'blue','Prometheus / Grafana',null,'icPulse',54);
row(725,1480,265,'blue','ELK',null,'icChart',54);
row(1020,1480,316,'blue','Jaeger / OTLP',null,'icNet',54);
text(1365,1502,'本人：用量 / MCP 观测',17,C.white,500,'start',486);
text(1365,1533,'管理员：站点访问 / 注册统计',16,C.muted,400,'start',486);
text(40,1585,'TAgent · Architecture overview',13,C.faint,500);
text(1880,1585,'依据本地当前实现绘制 · 2026-10-10',13,C.faint,400,'end');
out.push('</svg>');
const svg=out.join('\n');
fs.writeFileSync(base+'.svg',svg);
fs.writeFileSync(base+'.html',`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TAgent 2.0 端到端架构</title><style>html,body{margin:0;background:#060d1f}svg{display:block;width:100%;height:auto} @media print{@page{size:landscape;margin:0}svg{max-height:100vh}}</style>${svg}</html>`);
console.log('Generated SVG and HTML:',base);
