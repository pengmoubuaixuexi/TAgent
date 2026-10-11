const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http'),os=require('node:os');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../main/resources/static'),screenshots=fs.mkdtempSync(path.join(os.tmpdir(),'tagent-outcomes-ui-'));
const run={runId:'run-a',agentId:'agent-a',agentVersion:7,question:'整理本次研究',answer:'# 初始回答\n<img src=x onerror=window.xss=1>',status:'COMPLETED',createdAt:'2026-10-09',updatedAt:'2026-10-09'};
let artifacts=[],feedback={},requests=[],conflict=false;
const server=http.createServer((req,res)=>{let body='';req.on('data',d=>body+=d);req.on('end',()=>{
 const url=new URL(req.url,'http://localhost'),data=body?JSON.parse(body):undefined;requests.push({url:url.pathname,method:req.method,body:data,csrf:req.headers['x-csrf-token']});
 const send=(value,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(status===200?{code:'0000',data:value}:{code:String(status),info:'版本已改变，请重新读取'}));};
 if(url.pathname==='/api/v1/auth/csrf'){res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'fixture'}));}
 if(url.pathname==='/api/v1/history')return send([run]);if(url.pathname==='/api/v1/history/run-a')return send(run);
 if(url.pathname.endsWith('/runs/run-a/feedback')){if(req.method==='PUT'){feedback={...data,revision:(feedback.revision||0)+1,...(data.createEvalDraft?{evalDatasetId:'private-eval',evalCaseId:'draft-case'}:{})};}return send(feedback);}
 if(url.pathname.endsWith('/outcomes/artifacts')){if(req.method==='GET')return send(artifacts.map(a=>a.at(-1)));const a={...data,artifactId:'a'+(artifacts.length+1),revision:1,currentRevision:1,agentVersion:7};artifacts.push([a]);return send(a);}
 const match=url.pathname.match(/\/outcomes\/artifacts\/(a\d+)(?:\/(revisions|export))?$/);
 if(match){const list=artifacts.find(a=>a[0].artifactId===match[1]),current=list.at(-1);if(match[2]==='revisions')return send(list.toReversed().map(a=>({...a,createdAt:'2026-10-09'})));
  if(req.method==='PUT'){if(conflict)return send(null,409);const a={...current,...data,revision:current.revision+1,currentRevision:current.revision+1};list.push(a);return send(a);}
  const requested=Number(url.searchParams.get('revision')||0),a=requested?list.find(a=>a.revision===requested):current;return send({...a,currentRevision:current.revision});}
 const file=path.resolve(root,url.pathname.slice(1));if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);return res.end();}res.writeHead(200,{'Content-Type':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});res.end(fs.readFileSync(file));
});});
(async()=>{await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  await page.addInitScript(()=>Object.defineProperty(navigator,'clipboard',{value:{writeText:async text=>{window.copiedText=text;}}}));
  await page.goto('http://127.0.0.1:'+server.address().port+'/outcomes.html?runId=run-a');await page.waitForFunction(()=>!document.getElementById('new-markdown').disabled);
  const ready=()=>page.waitForFunction(()=>!document.getElementById('reload').disabled),click=async selector=>{await page.click(selector);await ready();};
  assert.equal(await page.locator('#run-answer img').count(),0);assert.equal(await page.isChecked('#create-eval'),false);
  await click('#new-markdown');assert((await page.inputValue('#artifact-markdown')).includes('# 初始回答'));await click('#save-artifact');
  assert.equal(artifacts[0][0].agentVersion,7);assert.equal(await page.locator('#artifact-form img').count(),0);assert.equal(await page.evaluate(()=>window.xss),undefined);
  await page.fill('#artifact-markdown','# 修改正文');await click('#save-artifact');assert.equal(artifacts[0].length,2);assert(artifacts[0][0].content.markdown.includes('初始回答'));
  await page.selectOption('#revision-select','1');await ready();assert.equal(await page.isEnabled('#save-artifact'),false);assert.equal(await page.isEnabled('#artifact-markdown'),false);
  assert((await page.getAttribute('#export-artifact','href')).endsWith('revision=1'));await click('#copy-artifact');assert((await page.evaluate(()=>window.copiedText)).includes('artifactId=a1，revision=1，runId=run-a'));
  await page.selectOption('#revision-select','2');await ready();conflict=true;await page.fill('#artifact-markdown','冲突时保留内容');await click('#save-artifact');assert.equal(await page.inputValue('#artifact-markdown'),'冲突时保留内容');conflict=false;
  await click('#new-table');await page.fill('#table-columns','名称\t值');await page.fill('#table-rows','=1+1\t"<img src=x onerror=window.xss=1>\n第二行"');assert.equal(await page.locator('#table-preview img').count(),0);await click('#save-artifact');assert.equal(artifacts[1][0].content.rows[0][0],'=1+1');assert(artifacts[1][0].content.rows[0][1].includes('\n'));
  await page.fill('#artifact-title','表格改名');await click('#save-artifact');assert.deepEqual(artifacts[1][1].content,artifacts[1][0].content,'multiline TSV must roundtrip when updating a title');
  const codeSource='<!doctype html>\n<script>window.codeExecuted=1</script>\n  <p>缩进与尾部换行</p>\n';
  await click('#new-code');await page.fill('#code-filename','index.html');await page.fill('#code-language','html');await page.fill('#artifact-code',codeSource);await click('#save-artifact');
  assert.equal(artifacts[2][0].format,'CODE');assert.deepEqual(artifacts[2][0].content,{filename:'index.html',language:'html',code:codeSource});
  assert.equal(await page.locator('#code-editor script,#code-editor img').count(),0);assert.equal(await page.evaluate(()=>window.codeExecuted),undefined);
  assert.equal(await page.textContent('#export-artifact'),'下载 index.html');assert((await page.getAttribute('#export-artifact','href')).endsWith('a3/export?revision=1'));
  await page.fill('#artifact-code',codeSource+'<!-- 第 2 版 -->\n');await click('#save-artifact');assert.equal(artifacts[2][1].content.code,codeSource+'<!-- 第 2 版 -->\n');assert.equal(artifacts[2][0].content.code,codeSource,'source revisions retain original text');
  await page.selectOption('#revision-select','1');await ready();assert.equal(await page.isEnabled('#artifact-code'),false);assert.equal(await page.isEnabled('#code-filename'),false);assert.equal(await page.isEnabled('#code-language'),false);
  await click('#copy-artifact');assert((await page.evaluate(()=>window.copiedText)).endsWith(codeSource),'copy contains original source without markdown formatting');
  // Existing files may use CRLF; a textarea normalizes them, but a title edit must not.
  artifacts[2][1].content.code=codeSource.replace(/\n/g,'\r\n');
  await page.selectOption('#revision-select','2');await ready();await page.fill('#artifact-title','只修改名称');await click('#save-artifact');
  assert.equal(artifacts[2][2].content.code,codeSource.replace(/\n/g,'\r\n'),'metadata-only edit preserves CRLF source exactly');
  await page.fill('#artifact-code',codeSource+'<!-- CRLF 修订 -->\n');await click('#save-artifact');
  assert.equal(artifacts[2][3].content.code,(codeSource+'<!-- CRLF 修订 -->\n').replace(/\n/g,'\r\n'),'source edit preserves existing CRLF convention');
  await page.fill('#feedback-comment','需要查证来源');await click('#save-feedback');assert.equal(requests.filter(r=>r.url.endsWith('/feedback')&&r.method==='PUT').at(-1).body.createEvalDraft,false);assert.equal(feedback.evalDatasetId,undefined);
  await page.check('#create-eval');await click('#save-feedback');assert.equal(await page.isVisible('#eval-link'),true);assert.equal(await page.isEnabled('#create-eval'),false);
  await page.screenshot({path:path.join(screenshots,'desktop.png'),fullPage:true});
  for(const width of [320,768,1440]){await page.setViewportSize({width,height:900});await page.waitForTimeout(30);assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'layout overflow '+width);if(width===320)await page.screenshot({path:path.join(screenshots,'mobile.png'),fullPage:true});}
  assert(requests.filter(r=>['PUT','POST'].includes(r.method)).every(r=>r.csrf==='fixture'));assert.deepEqual(errors,[]);console.log('PASS: outcomes immutable document/table/code revisions, exact source and CRLF edits, inert HTML source, conflict preservation, explicit reuse, opt-in private Eval draft, CSRF and responsive layouts.');console.log('Screenshots:',screenshots);
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
