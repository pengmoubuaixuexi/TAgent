const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../main/resources/static'),calls=[];
let connections=[],allow=true,available=true; const usageCalls=[];
const server=http.createServer((req,res)=>{let body='';req.on('data',data=>body+=data);req.on('end',()=>{
 const route=new URL(req.url,'http://localhost').pathname,input=body?JSON.parse(body):undefined;
 const send=data=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:'0000',data}));};
 if(route==='/api/v1/auth/csrf'){res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'synthetic-csrf'}));}
 if(route==='/api/v1/workspace/usage'){const selected=new URL(req.url,'http://localhost').searchParams.get('runId');usageCalls.push(selected);return send({filteredRunId:selected,runSummary:selected?{calls:1,confirmedTokens:0,unknownCalls:1,pendingCalls:0,billingSources:['PLATFORM'],costAmount:null,pricingStatus:'UNKNOWN'}:null,day:'2026-10-09',dayTimeZone:'UTC',today:{platformCalls:2,platformReservedTokens:90,confirmedPlatformTokens:30},activeRuns:1,limits:{platformDailyTokens:100,platformDailyCalls:20,concurrentRuns:3},attempts:[{model:'<img src=x onerror=window.bad=1>',billingSource:'PLATFORM',purpose:'CHAT_HELPER',provider:'provider.test',actualTokens:null,reservedTokens:60,usageStatus:'UNKNOWN',outcome:'ERROR',pricingStatus:'UNKNOWN',costAmount:null,currency:null,runId:'run-a',createdAt:'2026-10-09'}]});}
 if(route.startsWith('/api/v1/workspace/model-connections')){
  calls.push({route,method:req.method,input,csrf:req.headers['x-csrf-token']});
  if(route.endsWith('/auxiliary-policy')){allow=input.allowPlatformAuxiliaries;return send({});}
  if(req.method==='GET')return send({connections,allowPlatformAuxiliaries:allow,secretsAvailable:available,allowedHosts:['api.openai.com']});
  if(route.endsWith('/verify')){connections[0].verifiedAt='2026-10-09';return send({});}
  if(route.endsWith('/rotate')){connections[0].credentialVersion++;connections[0].status=1;connections[0].verifiedAt=null;return send({});}
  if(req.method==='DELETE'){connections[0].status=0;return send({});}
  connections=[{apiId:'personal-api',name:input.name,modelName:input.modelName,baseUrl:input.baseUrl,status:1,credentialVersion:1,maskedKey:'••••••••',verifiedAt:null}];allow=input.allowPlatformAuxiliaries;return send({apiId:'personal-api'});
 }
 const file=path.resolve(root,'.'+route);if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);return res.end();}
 res.writeHead(200,{'Content-Type':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});fs.createReadStream(file).pipe(res);
});});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const page=await browser.newPage(),errors=[],external=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',route=>{if(new URL(route.request().url()).hostname==='127.0.0.1')return route.continue();external.push(route.request().url());return route.abort();});
 try{
  await page.goto('http://127.0.0.1:'+server.address().port+'/models.html');
  await page.waitForFunction(()=>document.getElementById('allowedHosts').textContent.includes('api.openai.com'));
  assert.equal(await page.isChecked('#platformAuxiliaries'),true);assert.equal(await page.isChecked('#createAuxiliaries'),false);
  await page.fill('[name=name]','<img src=x onerror=window.bad=1>');await page.fill('[name=modelName]','test-chat');
  await page.fill('[name=baseUrl]','https://api.openai.com/v1');await page.fill('[name=apiKey]','synthetic-create-key');await page.click('#createConnection');
  await page.waitForFunction(()=>document.querySelectorAll('.connection').length===1);
  assert.equal(await page.inputValue('[name=apiKey]'),'');assert.equal((await page.content()).includes('synthetic-create-key'),false);
  assert.equal(calls.find(c=>c.method==='POST').input.allowPlatformAuxiliaries,false);assert.equal(await page.locator('.connection img').count(),0);
  assert.equal(calls.filter(c=>c.route.endsWith('/verify')).length,0,'saving must not trigger provider verification');
  await page.getByRole('button',{name:'验证连接（不生成内容）'}).click();await page.waitForFunction(()=>document.querySelector('.details').textContent.includes('连接验证成功'));
  await page.getByRole('button',{name:'轮换密钥'}).click();await page.fill('#newKey','synthetic-rotation-key');await page.getByRole('button',{name:'保存新密钥'}).click();
  await page.waitForFunction(()=>!document.getElementById('rotateDialog').open&&document.querySelector('.details').textContent.includes('密钥版本 2'));
  assert.equal(await page.inputValue('#newKey'),'');assert.equal((await page.content()).includes('synthetic-rotation-key'),false);
  await page.getByRole('button',{name:'撤销连接'}).click();await page.waitForFunction(()=>document.querySelector('.details').textContent.includes('已撤销'));
  await page.check('#platformAuxiliaries');await page.click('#savePolicy');await page.waitForFunction(()=>document.getElementById('message').textContent.includes('设置已保存'));assert.equal(allow,true);
  available=false;await page.click('#refresh');await page.waitForFunction(()=>!document.getElementById('availability').hidden);assert.equal(await page.isDisabled('#createConnection'),true);
  assert(calls.filter(c=>c.method!=='GET').every(c=>c.csrf==='synthetic-csrf'));
  assert.deepEqual(await page.evaluate(()=>Object.values(localStorage)),[]);assert.deepEqual(errors,[]);assert.deepEqual(external,[]);
  await page.goto('http://127.0.0.1:'+server.address().port+'/usage.html');await page.waitForFunction(()=>document.getElementById('tokenQuota').textContent==='90 / 100');
  assert((await page.textContent('#attempts')).includes('实际 Token：未知'));assert((await page.textContent('#attempts')).includes('价格未知'));
  assert.equal(await page.locator('#attempts img').count(),0);assert.equal(await page.textContent('#runQuota'),'1 / 3');
  const selected='run-a<img src=x onerror=window.bad=1>';
  await page.goto('http://127.0.0.1:'+server.address().port+'/usage.html?runId='+encodeURIComponent(selected));
  await page.waitForFunction(()=>!document.getElementById('runFilter').hidden);
  assert.equal(usageCalls.at(-1),selected);assert.equal(await page.textContent('#filterLabel'),'筛选运行：'+selected);
  assert((await page.textContent('#runSummary')).includes('价格未知'));assert((await page.textContent('#runSummary')).includes('用量未知：1'));
  assert.equal(await page.locator('#runFilter img').count(),0);assert.equal(await page.textContent('#tokenQuota'),'90 / 100');
  await page.getByRole('link',{name:'查看全部运行'}).click();await page.waitForFunction(()=>document.getElementById('tokenQuota').textContent==='90 / 100');
  assert.equal(usageCalls.at(-1),null);assert.equal(await page.isHidden('#runFilter'),true);
  assert.deepEqual(errors,[]);assert.deepEqual(external,[]);
  console.log('PASS models UI: create, explicit consent, verify, rotate, revoke, disabled encryption, CSRF, secret clearing, XSS and offline requests');
 } finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;server.close();});
