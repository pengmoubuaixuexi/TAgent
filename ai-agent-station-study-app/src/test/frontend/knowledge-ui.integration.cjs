const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http'),os=require('node:os');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../main/resources/static'),screenshots=fs.mkdtempSync(path.join(os.tmpdir(),'tagent-knowledge-ui-'));
let requests=[],task={taskId:'private-task-id',fileId:'rf_private-id',fileName:'论文 <img src=x onerror=window.injected=1>.txt',knowledgeTag:'我的论文',fileSize:6000,status:'FAILED',stage:'INDEXING',progress:85,chunkCount:12,attempt:1,error:'关键词索引写入未完成，原件已保留。'},files=[{ragId:'old-file',fileName:'已有资料.pdf',knowledgeTag:'旅行',status:1,fileSize:1000}],searchFailure=false;
const server=http.createServer((req,res)=>{let input='';req.on('data',d=>input+=d);req.on('end',()=>{
 const url=new URL(req.url,'http://localhost'),route=url.pathname;
 requests.push({method:req.method,path:route,query:url.search,csrf:req.headers['x-csrf-token'],input});
 const send=(data,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify({code:status<300?'0000':String(status),data,info:status>=400?'检索服务暂不可用':'成功'}));};
 if(route==='/api/v1/auth/csrf'){res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'fixture'}));}
 if(route==='/api/v1/auth/me')return send({username:'测试用户'});
 if(route==='/api/v1/knowledge')return send({tasks:task?[task]:[],files});
 if(route.endsWith('/uploads')){task={...task,status:'QUEUED',stage:'QUEUED',error:null};return send(task,202);}
 if(route.endsWith('/retry')){task={...task,status:'QUEUED',error:null};return send(task,202);}
 if(route.includes('/tasks/')&&req.method==='DELETE'){task=task.status==='PROCESSING'?{...task,status:'CANCEL_REQUESTED'}:null;return send(task||{});}
 if(route.endsWith('/chunks'))return send({fileName:'预览资料',knowledgeTag:'旅行',pageSize:10,total:11,chunks:[{content:'<script>window.injected=1</script>\n引用文本',title:'片段 '+(Number(url.searchParams.get('page'))+1)}]});
 if(route.endsWith('/search-test'))return searchFailure?send(null,503):send({branches:{vector:'timeout',bm25:'success'},degraded:true,latencyMs:100,documents:[{content:'只使用关键词召回 <img src=x onerror=window.injected=1>',source:'已有资料.pdf'}]});
 const file=path.resolve(root,'.'+route);if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);return res.end('missing');}
 res.writeHead(200,{'Content-Type':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});fs.createReadStream(file).pipe(res);
});});
(async()=>{await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const base='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'}),page=await browser.newPage({viewport:{width:1440,height:1100}}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
 try{
  await page.goto(base+'/knowledge.html');await page.waitForFunction(()=>document.getElementById('file-list').textContent.includes('关键词索引'));
  assert.equal(await page.locator('#file-list img').count(),0);assert.equal(await page.evaluate(()=>window.injected),undefined);
  assert.equal((await page.textContent('#file-list')).includes('private-task-id'),false);
  await page.getByRole('button',{name:'重试入库'}).click();await page.waitForFunction(()=>document.getElementById('file-list').textContent.includes('等待处理'));
  assert.equal(requests.filter(r=>r.path.endsWith('/retry')).length,1);assert.equal(requests.find(r=>r.path.endsWith('/retry')).csrf,'fixture');
  task={...task,status:'PROCESSING',stage:'CLEANING',progress:5};await page.locator('#refresh').click();await page.waitForFunction(()=>document.getElementById('file-list').textContent.includes('清理上次'));
  await page.getByRole('button',{name:'停止并删除'}).click();await page.waitForFunction(()=>document.getElementById('file-list').textContent.includes('正在停止入库'));
  assert.equal(await page.getByRole('button',{name:'停止并删除'}).count(),0);
  task={...task,status:'DELETE_FAILED',error:'入库已停止，请删除清理'};await page.locator('#refresh').click();await page.waitForFunction(()=>document.getElementById('file-list').textContent.includes('入库已停止'));
  await page.locator('#file-list tr').first().getByRole('button',{name:'删除',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('#file-list tr').length===1);
  await page.getByRole('button',{name:'查看片段'}).click();await page.waitForFunction(()=>document.getElementById('preview-page').textContent.includes('1 / 2'));
  assert.equal(await page.locator('#preview-chunks script').count(),0);assert.equal(await page.evaluate(()=>window.injected),undefined);
  await page.locator('#next-page').click();await page.waitForFunction(()=>document.getElementById('preview-page').textContent.includes('2 / 2'));assert.equal(await page.locator('#next-page').isDisabled(),true);
  await page.locator('#close-preview').click();await page.selectOption('#search-tag','旅行');await page.fill('#search-query','北京');await page.locator('#search').click();
  await page.waitForFunction(()=>document.getElementById('search-state').textContent.includes('部分检索分支'));
  assert.equal(await page.locator('#search-results img').count(),0);assert.equal(requests.filter(r=>r.path.endsWith('/search-test')).at(-1).csrf,'fixture');
  searchFailure=true;await page.locator('#search').click();await page.waitForFunction(()=>document.getElementById('search-state').textContent.includes('检索服务暂不可用'));assert.equal(await page.locator('#search-results article').count(),0);
  task={taskId:'uploaded',fileId:'rf_upload',fileName:'新资料.txt',knowledgeTag:'旅行'};
  await page.fill('#knowledge-tag','旅行');await page.setInputFiles('#files',{name:'新资料.txt',mimeType:'text/plain',buffer:Buffer.from('fixture')});await page.locator('#upload').click();
  await page.waitForFunction(()=>document.getElementById('notice').textContent.includes('原件已保存'));assert.equal(requests.filter(r=>r.path.endsWith('/uploads')).at(-1).csrf,'fixture');
  assert((await page.textContent('#file-list')).includes('等待处理'));
  await page.screenshot({path:path.join(screenshots,'knowledge-desktop.png'),fullPage:true});
  for(const width of [1024,720,390,320]){await page.setViewportSize({width,height:950});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'overflow at '+width);}
  await page.screenshot({path:path.join(screenshots,'knowledge-mobile.png'),fullPage:true});
  assert.deepEqual(errors,[]);console.log('PASS: upload accepted state, retry/cancel/delete, CSRF, pagination, escaped content, retrieval degradation/failure and 320–1440px layouts.');console.log('Screenshots:',screenshots);
 }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);server.close();process.exitCode=1;});
