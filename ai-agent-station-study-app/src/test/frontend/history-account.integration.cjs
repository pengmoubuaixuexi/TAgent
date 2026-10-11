const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http'),os=require('node:os');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../main/resources/static'),calls=[];
const run={runId:'test-run',status:'COMPLETED',strategy:'flow',agentVersion:2,question:'<img src=x onerror=window.bad=1>',answer:'最终答案',createdAt:'2026-10-09T00:00:00Z',updatedAt:'2026-10-09T00:01:00Z',eventsTruncated:1,events:[{eventType:'tool_result',createdAt:1,payload:{preview:'<script>window.bad=1</script>'}}]};
const server=http.createServer((req,res)=>{let body='';req.on('data',chunk=>body+=chunk);req.on('end',()=>{
 const u=new URL(req.url,'http://localhost'),route=u.pathname;const send=data=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:'0000',data}));};
 if(route==='/api/v1/auth/csrf'){res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'synthetic'}));}
 if(route==='/api/v1/auth/me')return send({userId:'alice',username:'Alice'});
 if(route==='/api/v1/workspace/model-connections')return send({connections:[]});
 if(route==='/api/v1/auth/password'){calls.push({input:JSON.parse(body),csrf:req.headers['x-csrf-token']});return send({});}
 if(route==='/api/v1/history')return send(u.searchParams.get('offset')==='20'?[]:Array.from({length:20},(_,i)=>({...run,runId:'test-run'+i})));
 if(route.startsWith('/api/v1/history/'))return send({...run,runId:route.split('/').pop()});
 const file=path.resolve(root,'.'+route);if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);return res.end();}
 res.writeHead(200,{'Content-Type':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});fs.createReadStream(file).pipe(res);
});});
(async()=>{await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'}),page=await browser.newPage(),errors=[],external=[];
 page.on('pageerror',e=>errors.push(e.message));await page.route('**/*',r=>{if(new URL(r.request().url()).hostname==='127.0.0.1')return r.continue();external.push(r.request().url());return r.abort();});
 const base='http://127.0.0.1:'+server.address().port,screens=fs.mkdtempSync(path.join(os.tmpdir(),'tagent-history-account-'));
 try{
  await page.goto(base+'/history.html?runId=test-run');await page.waitForSelector('#detail pre');assert.match(await page.textContent('#detail'),/最终答案/);assert.match(await page.textContent('#detail'),/保存上限/);assert.equal(await page.locator('img').count(),0);assert.equal(await page.evaluate(()=>window.bad),undefined);
  assert.match(await page.getAttribute('#detail a[href^="/outcomes"]','href'),/outcomes.html\?runId=test-run/);assert.match(await page.getAttribute('#detail a[href^="/usage"]','href'),/usage.html\?runId=test-run/);await page.click('#next');await page.waitForFunction(()=>document.getElementById('page').textContent.includes('2'));assert.match(await page.textContent('#runs'),/暂无/);await page.click('#previous');await page.waitForSelector('.run');await page.locator('.run').nth(1).click();await page.waitForFunction(()=>location.search.includes('test-run1'));
  await page.setViewportSize({width:1280,height:900});await page.screenshot({path:path.join(screens,'history-desktop.png'),fullPage:true});
  for(const width of [320,390,768,1280]){await page.setViewportSize({width,height:900});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'history overflow '+width);}
  await page.goto(base+'/account.html');await page.waitForFunction(()=>document.getElementById('accountName').textContent==='Alice');assert.match(await page.textContent('#modelState'),/平台模型/);
  await page.fill('#currentPassword','old-test-password');await page.fill('#newPassword','new-test-password');await page.fill('#confirmPassword','mismatch-password');await page.click('#savePassword');assert.match(await page.textContent('#passwordStatus'),/不一致/);assert.equal(calls.length,0);
  await page.fill('#confirmPassword','new-test-password');await page.click('#savePassword');await page.waitForFunction(()=>document.getElementById('passwordStatus').textContent.includes('密码已修改'));assert.equal(calls.length,1);assert.equal(calls[0].csrf,'synthetic');assert.deepEqual(Object.keys(calls[0].input).sort(),['currentPassword','newPassword']);assert.equal(await page.inputValue('#newPassword'),'');assert.equal(await page.evaluate(()=>JSON.stringify(localStorage).includes('new-test-password')),false);
  for(const width of [320,390,768,1280]){await page.setViewportSize({width,height:900});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'account overflow '+width);}
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(screens,'account-mobile.png'),fullPage:true});
  assert.deepEqual(errors,[]);assert.deepEqual(external,[]);console.log('PASS history/account local browser flows, owner-view UX, XSS, pagination, CSRF/password clearing, 320-1280px. Screenshots: '+screens);
 }finally{await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
