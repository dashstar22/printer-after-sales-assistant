import {serviceApi as api} from './api.js';
import {el,button,field,pairs,feedback,link} from './ui.js';

const sources={official_manual:'厂商官方手册',enterprise_policy:'企业规则',enterprise_faq:'企业 FAQ',self_authored_simulation:'模拟资料'};
const states={QUEUED:'已排队',RUNNING:'正在处理',RETRY_WAIT:'等待再次尝试',FAILED:'处理失败',SUCCEEDED:'任务已完成',WAITING_WORKER:'等待后台处理'};
const stages={REVIEW_REQUIRED:'待检查分块',PUBLISH_REQUESTED:'已申请发布',INDEXING:'准备索引',EMBEDDING:'处理文本表示',VECTOR_INDEXING:'建立检索资料',BM25_INDEXING:'建立关键词检索',VERIFYING:'核对完整性',ACTIVATING:'切换有效版本',PUBLISHED:'发布完成',DISABLED:'已停用'};
const failures={PURPOSE_NOT_ALLOWED:'当前用途许可不足。核实授权与服务配置，登记新修订后重新预览。',EXTERNAL_CALLS_DISABLED:'后台外部处理未启用。请联系负责人核对运行配置。',INDEX_ADAPTER_MISSING:'后台处理服务尚未配置，请联系负责人。',EMBEDDING_RESULT_UNKNOWN:'外部处理结果待核查，普通重试不能再次计费。请由负责人核对原回执。',EMBEDDING_UNKNOWN:'外部处理结果待核查，请先核对原回执与预算。',PERMISSION_REVOKED:'用途许可已经变化，请重新核实资料授权。'};
const clone=v=>JSON.parse(JSON.stringify(v));
const hash=async value=>[...new Uint8Array(await crypto.subtle.digest('SHA-256',value instanceof ArrayBuffer?value:new TextEncoder().encode(value)))].map(b=>b.toString(16).padStart(2,'0')).join('');

export function knowledgeScreen(host,ctx,initialId){
  const root=el('section',null,'knowledge-management');host.replaceChildren(root);
  const storageKey='printer.service.knowledge.v1.'+ctx.user.id;
  let disposed=false,busy=false,documents=[],nextCursor=null,doc=null,job=null,preview=null,reviewed=false,models=[],configs={},note='',pending=null,chosenFile=null,editor=false,revising=false;
  let draft=blank();
  function blank(){return {kind:'file',title:'',documentKey:'',version:'',sourceType:'enterprise_policy',modelScope:'MODELS',models:[],description:'',checkedOn:'',useCondition:'',distributionAllowed:false,chunkSize:500,chunkOverlap:50,text:'',downloadUrl:'',officialIndex:'',termsUrl:'',brand:'',region:'',language:'',acquiredOn:'',coverModels:'',permissions:{embedding:{allowed:false,configurationId:''},rerank:{allowed:false,configurationId:''},chat:{allowed:false,configurationId:''}}};}
  function remember(){try{sessionStorage.setItem(storageKey,JSON.stringify({id:doc?.id||null,pending}));}catch{note='浏览器未保存定位信息，可从资料列表或资料链接重新查回任务。';}}
  function saved(){try{return JSON.parse(sessionStorage.getItem(storageKey)||'{}');}catch{return {};}}
  function allowedError(e){return e.status===401||e.status===403&&['EMAIL_BIND_REQUIRED','EMAIL_BINDING_REQUIRED','PASSWORD_CHANGE_REQUIRED','PASSWORD_LOGIN_REQUIRED','PASSWORD_AUTHENTICATION_REQUIRED'].includes(e.code);}
  async function run(action){return ctx.run(async()=>{if(disposed||busy)return;busy=true;render();try{await action();}catch(e){if(allowedError(e)){root.replaceChildren();ctx.onAuthError(e);return;}note=feedback(e);if(e.code==='PREVIEW_STALE'||e.code==='VERSION_CONFLICT'){preview=null;reviewed=false;}throw e;}finally{busy=false;if(!disposed)render();}});}
  function input(key,label,{type='text',max,required=true,rows}={}){
    const c=el(rows?'textarea':'input');c.id='knowledge-'+key;if(!rows)c.type=type;else c.rows=rows;
    c.value=draft[key]??'';c.required=required;if(max)c.maxLength=max;
    c.addEventListener('input',()=>{draft[key]=type==='number'?Number(c.value):c.value;});return field(label,c);
  }
  function select(key,label,options){const c=el('select');c.id='knowledge-'+key;for(const [value,text]of Object.entries(options))c.append(new Option(text,value));c.value=draft[key];c.addEventListener('change',()=>{draft[key]=c.value;if(key==='sourceType'&&draft.kind==='faq'&&!['enterprise_faq','self_authored_simulation'].includes(c.value))draft.kind='file';render();});return field(label,c);}
  function check(id,label,value,change){const c=el('input');c.type='checkbox';c.id=id;c.checked=value;c.addEventListener('change',()=>change(c.checked));const w=field(label,c);w.classList.add('knowledge-check');return w;}
  function metadata(requestId){
    const source={description:draft.description,checkedOn:draft.checkedOn};
    if(draft.sourceType==='official_manual')Object.assign(source,...['downloadUrl','officialIndex','termsUrl','brand','region','language','acquiredOn'].map(k=>({[k]:draft[k]})),{coverModels:draft.coverModels.split(/[,，\n]/).map(x=>x.trim()).filter(Boolean)});
    const processingPermissions={};for(const p of ['embedding','rerank','chat'])processingPermissions[p]=draft.permissions[p].allowed?{allowed:true,configurationId:draft.permissions[p].configurationId}:{allowed:false};
    return {requestId,title:draft.title,documentKey:draft.documentKey,version:draft.version,sourceType:draft.sourceType,isSimulated:draft.sourceType==='self_authored_simulation',modelScope:draft.modelScope,models:draft.modelScope==='GENERAL'?[]:[...draft.models],source,useCondition:draft.useCondition,distributionAllowed:draft.distributionAllowed,processingPermissions,chunkSize:draft.chunkSize,chunkOverlap:draft.chunkOverlap,...(revising?{expectedVersion:doc.version}:{})};
  }
  async function list(more=false){const r=await api.get('/api/admin/knowledge?cursor='+(more?nextCursor:0)+'&pageSize=20');if(disposed)return;documents=more?[...documents,...r.items]:r.items;nextCursor=r.nextCursor;}
  async function load(id){
    const value=await api.get('/api/admin/knowledge/'+encodeURIComponent(id));if(disposed)return;
    if(doc?.id!==value.id||doc?.revision!==value.revision||doc?.version!==value.version){preview=null;reviewed=false;}
    doc=value;
    const latest=(value.publicationRequests||[]).filter(j=>j.revision===value.revision).at(-1);
    job=latest?await api.get('/api/admin/knowledge/jobs/'+encodeURIComponent(latest.jobId)):null;
    if(disposed)return;remember();
  }
  async function openDocument(id){editor=false;revising=false;chosenFile=null;draft=blank();await load(id);note='已查回资料；发布状态以当前修订和任务回执为准。';}
  function fillMetadata(m){const s=m.source;draft={...blank(),...Object.fromEntries(['title','documentKey','version','sourceType','modelScope','models','useCondition','distributionAllowed','chunkSize','chunkOverlap'].map(k=>[k,clone(m[k])])),...s,description:s.description,checkedOn:s.checkedOn,coverModels:(s.coverModels||[]).join('\n'),permissions:clone(m.processingPermissions)};}
  function edit(){
    fillMetadata(doc.metadata);
    revising=true;editor=true;chosenFile=null;draft.version='';note='新修订会保留旧版。请填写新版本并重新选择原件或输入完整 FAQ；新修订须重新检查分块。';
  }
  async function upload(){
    if(pending&&pending.kind!=='upload')throw new Error('请先核对上次操作。');
    if(draft.kind==='faq'&&!['enterprise_faq','self_authored_simulation'].includes(draft.sourceType))throw new Error('FAQ 编辑仅适用于企业 FAQ 或模拟资料。');
    if(draft.kind==='file'&&!chosenFile)throw new Error('请选择资料原件；刷新后须重新选择同一文件。');
    if(chosenFile&&chosenFile.size>20*1024*1024)throw new Error('单文件最多 20MB，请提供文本版资料或 FAQ。');
    const requestId=pending?.requestId||crypto.randomUUID(),body=metadata(requestId),target=pending?.target??(revising?doc.id:null);
    if(pending?.metadata?.expectedVersion!==undefined)body.expectedVersion=pending.metadata.expectedVersion;
    const contentHash=await hash(draft.kind==='faq'?draft.text:await chosenFile.arrayBuffer());
    const fingerprint=await hash(JSON.stringify({body,kind:draft.kind,target,contentHash,name:draft.kind==='file'?chosenFile.name:'faq.md'}));
    if(pending&&pending.fingerprint!==fingerprint)throw new Error('内容与原请求不同。请先从资料列表核对原结果，结束旧请求后再提交新内容。');
    pending={kind:'upload',requestId,fingerprint,target,metadata:body,inputKind:draft.kind};remember();
    const path='/api/admin/knowledge'+(target?'/'+target+'/revisions':'');let result;
    if(draft.kind==='faq')result=await api.write(path,{...body,text:draft.text});
    else{const data=new FormData();data.append('metadata',JSON.stringify(body));data.append('file',chosenFile);result=await api.upload(path,data);}
    if(disposed)return;pending=null;doc=result;remember();editor=false;draft=blank();chosenFile=null;preview=null;reviewed=false;job=null;await list();note='资料已登记，尚未发布。请按顺序检查全部分块。';
  }
  async function write(kind,path,body){
    if(pending&&pending.kind!==kind)throw new Error('请先核对上次操作。');
    if(!pending){pending={kind,path,documentId:doc.id,body:{...body,requestId:crypto.randomUUID()}};remember();}
    const target=pending.documentId;await api.write(pending.path,pending.body);if(disposed)return;pending=null;remember();await load(target);await list();note=kind==='publish'?'发布申请已受理，请查看原任务实际进度。':kind==='retry'?'已申请重试，原失败历史保留。':'指定修订已停用。';
  }
  async function nextPreview(){
    const cursor=preview?.nextCursor??0;
    const query=new URLSearchParams({revision:String(doc.revision),cursor:String(cursor),pageSize:'20'});if(cursor&&preview?.previewToken)query.set('previewToken',preview.previewToken);
    const r=await api.get('/api/admin/knowledge/'+doc.id+'/preview?'+query);if(disposed)return;
    // New server snapshot invalidates the previous human confirmation.
    preview=r;reviewed=false;doc={...doc,...Object.fromEntries(['version','revision','contentHash','layoutHash','configHash'].map(k=>[k,r[k]]))};
  }
  function form(){
    const area=el('section',null,'workspace-panel knowledge-editor');area.append(el('h2',revising?'登记新修订':'登记知识资料'));
    const f=el('form');f.id='knowledge-form';
    f.append(select('kind','登记方式',{file:'上传文本文件',faq:'编辑 FAQ'}),select('sourceType','资料来源',sources));
    const grid=el('div',null,'knowledge-fields');grid.append(input('title','资料标题',{max:200}),input('documentKey','资料编号',{max:96}),input('version','业务版本',{max:64}),select('modelScope','适用范围',{MODELS:'指定已登记型号',GENERAL:'通用企业资料'}));f.append(grid);
    if(revising)f.querySelector('#knowledge-documentKey').readOnly=true;
    if(draft.modelScope==='MODELS'){
      const c=el('select');c.id='knowledge-models';c.multiple=true;c.size=Math.min(5,Math.max(2,models.length));c.required=true;
      for(const model of models){const o=new Option(model.modelCode+(model.isDemo?'（演示型号）':''),model.modelCode);o.selected=draft.models.includes(model.modelCode);c.append(o);}
      c.addEventListener('change',()=>draft.models=[...c.selectedOptions].map(o=>o.value));f.append(field('适用型号（可多选，最多 20 个）',c));
    }
    f.append(input('description','来源与核查说明',{max:2000,rows:3}),input('checkedOn','来源核查日期',{type:'date'}),input('useCondition','使用条件与限制',{max:2000,rows:3}));
    if(draft.sourceType==='official_manual'){
      const group=el('fieldset');group.append(el('legend','官方来源核查'));
      for(const [k,label,type]of [['downloadUrl','官方下载地址','url'],['officialIndex','官方索引地址','url'],['termsUrl','使用条款地址','url'],['brand','品牌','text'],['region','适用地区','text'],['language','语言','text'],['acquiredOn','获取日期','date']])group.append(input(k,label,{type,max:type==='url'?2048:64}));
      group.append(input('coverModels','官方覆盖型号（每行一个）',{rows:3,max:6500}));f.append(group);
    }
    const perms=el('fieldset');perms.append(el('legend','逐项外部处理许可'),el('p','仅在已核实资料许可及对应服务配置后勾选。未勾选仍可本地预览；三项全部获准后才能申请发布。','subtle'));
    for(const [p,label]of [['embedding','检索表示处理'],['rerank','候选相关性排序'],['chat','辅助回答生成']]){
      const grant=draft.permissions[p];perms.append(check('knowledge-'+p+'-allowed',label,grant.allowed,v=>{grant.allowed=v;render();}));
      if(grant.allowed){const s=el('select');s.id='knowledge-'+p+'-config';s.required=true;s.append(new Option('选择已登记配置',''));for(const id of configs[p]||[])s.append(new Option(id,id));s.value=grant.configurationId||'';s.addEventListener('change',()=>grant.configurationId=s.value);perms.append(field(label+'的服务配置',s));if(!(configs[p]||[]).length)perms.append(el('p','尚无已登记配置，请联系负责人核实。','admin-note'));}
    }f.append(perms,check('knowledge-distribution','允许管理员下载该修订原件',draft.distributionAllowed,v=>draft.distributionAllowed=v));
    const cut=el('details');cut.append(el('summary','分块设置'),input('chunkSize','分块字符上限（400～600）',{type:'number'}),input('chunkOverlap','重叠字符（0～100）',{type:'number'}));f.append(cut);
    if(draft.kind==='faq')f.append(input('text','完整 FAQ 正文',{rows:10,max:2000000}));
    else{const c=el('input');c.id='knowledge-file';c.type='file';c.accept='.pdf,.docx,.md,.markdown';c.required=!chosenFile;c.addEventListener('change',()=>{chosenFile=c.files[0]||null;});f.append(field('文本 PDF、DOCX 或 Markdown（最多 20MB）',c));if(chosenFile)f.append(el('p','已选择：'+chosenFile.name,'admin-note'));f.append(el('p','不识别扫描图片、复杂版式或宏。解析失败请改用文本版或 FAQ；上传不会自动发布。','subtle'));}
    const submit=el('button','保存并生成分块预览','primary-button');submit.type='submit';submit.id='knowledge-upload';submit.disabled=!!pending&&pending.kind!=='upload';
    f.append(submit);f.addEventListener('submit',e=>{e.preventDefault();run(upload);});area.append(f);return area;
  }
  function render(){
    if(disposed)return;root.replaceChildren();root.setAttribute('aria-busy',String(busy));
    const intro=el('div',null,'knowledge-guide');intro.append(el('strong','先核对原文，再交给助手使用。'),el('p','登记资料 → 检查分块 → 确认发布 → 核对生效。旧版在新版完整核验前继续服务。'));root.append(intro);
    if(note){const n=el('p',note,'admin-feedback');n.id='knowledge-feedback';n.setAttribute('role','status');root.append(n);}
    const listPanel=el('section',null,'workspace-panel');const heading=el('div',null,'panel-heading');heading.append(el('h2','资料目录'),button('刷新目录',()=>run(()=>list())),button('登记新资料',()=>{if(pending){note='请先核对原操作结果。';render();return;}editor=true;revising=false;draft=blank();chosenFile=null;render();},'primary-button'));listPanel.append(heading);
    const listing=el('ul',null,'knowledge-list');
    for(const d of documents){const current=doc?.id===d.id?doc:null;const row=el('li');const title=el('div');title.append(el('strong',current?.metadata.title||d.title),el('p',`${sources[current?.metadata.sourceType||d.sourceType]||'历史资料'} · ${current?.metadata.version||d.version} · 修订 ${current?.revision||d.revision} · ${(current?current.documentActive:d.documentActive)?'有有效服务版本':'当前未生效'}`,'subtle'));row.append(title);
      if(d.layoutVersion==='knowledge-layout-v2')row.append(button('查看资料与进度',()=>run(()=>openDocument(d.id))));else row.append(el('span','历史布局保留；新资料请另行登记','subtle'));listing.append(row);}
    if(!documents.length)listing.append(el('li','尚无资料。从登记新资料开始，或联系负责人核对历史资料。'));listPanel.append(listing);if(nextCursor)listPanel.append(button('读取更多资料',()=>run(()=>list(true))));root.append(listPanel);
    if(pending){const recover=el('section',null,'recovery');recover.id='knowledge-recovery';recover.append(el('h2','原操作结果待核对'),el('p','请求标识：'+(pending.requestId||pending.body?.requestId)),el('p',pending.kind==='upload'?'先刷新目录核对是否已登记；若需重试，保持原元数据、文件名和正文，使用相同请求。刷新不会自动上传。':'刷新查回原任务；需要时显式使用原请求重试，不创建另一个操作。'));
      if(pending.kind!=='upload')recover.append(button('使用原请求重试',()=>run(()=>write(pending.kind,pending.path,pending.body))));
      else recover.append(button('恢复原上传输入',()=>run(async()=>{if(pending.target)await load(pending.target);fillMetadata(pending.metadata);draft.kind=pending.inputKind;editor=true;revising=!!pending.target;chosenFile=null;note='元数据已恢复。请重新选择原文件，或重新输入完全相同的 FAQ 正文，再以原请求提交。';})));
      recover.append(button('已核对结果，结束原请求',()=>{if(!confirm('请先核对目录、资料或原任务。结束恢复不会撤销服务端已完成的操作。'))return;pending=null;remember();note='原请求恢复已结束，请按实际结果继续。';render();}));root.append(recover);}
    if(editor)root.append(form());
    if(doc){
      const panel=el('section',null,'workspace-panel');panel.id='knowledge-detail';const head=el('div',null,'panel-heading');head.append(el('h2',doc.metadata.title),button('刷新资料与原任务',()=>run(()=>load(doc.id))));panel.append(head);
      const active=doc.active===true&&doc.status==='READY';panel.append(el('p',active?'当前修订已生效，可用于新查询。':`当前修订尚未生效：${stages[doc.stage]||'等待核对'}。${doc.documentActive?'已有服务版本继续使用。':'不能据此确认资料可用。'}`,'knowledge-state'));
      panel.append(pairs([['资料编号 / 内部 ID',doc.metadata.documentKey+' / '+doc.id],['业务版本 / 修订',doc.metadata.version+' / '+doc.revision],['来源',sources[doc.metadata.sourceType]],['适用型号',doc.metadata.modelScope==='GENERAL'?'通用企业资料':doc.metadata.models.join('、')],['分块数量',doc.chunkCount],['使用条件',doc.metadata.useCondition]]),link('此资料的恢复链接','#/admin/knowledge/'+doc.id));
      const actions=el('div',null,'admin-actions');actions.append(button(preview?'重新开始检查分块':'开始检查分块',()=>run(async()=>{preview=null;reviewed=false;await nextPreview();})),button('登记新修订',()=>{if(pending)return;edit();render();}));
      if(doc.metadata.distributionAllowed)actions.append(link('下载当前修订原件','/api/admin/knowledge/'+doc.id+'/original?revision='+doc.revision,'secondary-button'));
      if(doc.stage!=='DISABLED')actions.append(button('停用当前修订',()=>{if(confirm('停用当前修订后，新查询将不再使用它；历史原件与引用保留。'))run(()=>write('disable','/api/admin/knowledge/'+doc.id+'/disable',{expectedVersion:doc.version,revision:doc.revision}));}));panel.append(actions);
      if(doc.activeGeneration?.revision&&doc.activeGeneration.revision!==doc.revision)actions.append(button('停用当前生效修订（修订 '+doc.activeGeneration.revision+'）',()=>{if(confirm('将停用当前仍在服务的旧修订，后续新查询不再使用它。'))run(()=>write('disable','/api/admin/knowledge/'+doc.id+'/disable',{expectedVersion:doc.version,revision:doc.activeGeneration.revision}));}));
      if(preview){const chunks=el('section',null,'knowledge-preview');chunks.id='knowledge-preview';chunks.append(el('h3','检查原文分块'),el('p','请逐块核对条件、警告、型号与步骤是否完整；系统不会代替人工判断资料质量。','subtle'));
        for(const c of preview.items){const a=el('article');a.append(el('h4','片段 '+(c.chunkIndex+1)),el('p',[c.sectionPath,c.pageStart?'物理页 '+c.pageStart+(c.pageEnd!==c.pageStart?'～'+c.pageEnd:''):'',`正文位置 ${c.offsetStart}～${c.offsetEnd}`].filter(Boolean).join(' · '),'subtle'),el('p',c.content,'knowledge-source'));const ids=el('details');ids.append(el('summary','定位标识'),el('p',c.chunkId,'subtle'));a.append(ids);chunks.append(a);}
        if(preview.nextCursor!==null)chunks.append(button('检查下一页分块',()=>run(nextPreview)));
        else if(preview.reviewComplete){chunks.append(check('knowledge-reviewed','已逐页检查本修订分块，确认来源、条件和用途许可无误',reviewed,v=>{reviewed=v;render();}));const publish=button('确认发布此修订',()=>run(()=>write('publish','/api/admin/knowledge/'+doc.id+'/publish',{expectedVersion:doc.version,revision:doc.revision,previewToken:preview.previewToken,contentHash:doc.contentHash,layoutHash:doc.layoutHash,configHash:doc.configHash})),'primary-button');publish.id='knowledge-publish';publish.disabled=!reviewed||!!pending||doc.stage==='DISABLED'||['embedding','rerank','chat'].some(p=>!doc.metadata.processingPermissions[p]?.allowed);chunks.append(publish);if(publish.disabled&&!reviewed)chunks.append(el('p','检查完毕后请明确勾选确认。','subtle'));if(['embedding','rerank','chat'].some(p=>!doc.metadata.processingPermissions[p]?.allowed))chunks.append(el('p','发布所需用途许可不足。核实后通过新修订更新，不能跳过许可。','admin-note'));}panel.append(chunks);
      }
      if(job){const area=el('section',null,'knowledge-job');area.id='knowledge-job';area.append(el('h3','原发布任务'),pairs([['任务标识',job.jobId],['实际状态',states[job.status]||'状态待核对'],['当前阶段',stages[job.stage]||job.stage],['重试轮次 / 本轮尝试',job.cycle+' / '+job.attempt]]));
        if(job.failureCode)area.append(el('p',(failures[job.failureCode]||'处理未完成。请核对资料许可、服务配置和原任务记录后再决定是否重试。')+' ['+job.failureCode+']','admin-feedback'));
        if(job.status==='FAILED')area.append(button('核对后重试原任务',()=>{if(confirm('请先核对失败原因及原回执。重试保留原任务与失败历史，不扩大资料许可或外部预算。'))run(()=>write('retry','/api/admin/knowledge/jobs/'+job.jobId+'/retry',{}));}));
        const history=el('details');history.append(el('summary','查看历史尝试与核验结果'));for(const a of job.attempts||[])history.append(el('p',`轮次 ${a.cycle} / 尝试 ${a.attempt}：${states[a.state]||a.state} · ${stages[a.stage]||a.stage}${a.failureCode?' · '+a.failureCode:''}`));for(const g of job.generations||[])history.append(el('p',`索引核验：关键词 ${g.bm25Verified?'通过':'未完成'} / 语义 ${g.vectorVerified?'通过':'未完成'}；生效 ${g.publishedAt?'有记录':'尚无记录'}`));area.append(history);panel.append(area);
      }root.append(panel);
    }
    root.querySelectorAll('button,input,select,textarea').forEach(c=>c.disabled ||= busy);
  }
  return {async open(){
    const s=saved();if(s.pending&&['upload','publish','retry','disable'].includes(s.pending.kind))pending=s.pending;
    const options=await api.get('/api/admin/knowledge/options');configs=options.processingConfigurations;
    let cursor=0;do{const page=await api.get('/api/admin/models?cursor='+cursor+'&pageSize=100');models.push(...page.items);cursor=page.nextCursor;}while(cursor&&!disposed);
    await list();const id=initialId||s.id;if(id)try{await load(id);}catch(e){if(e.status!==404)throw e;note='原资料定位不存在，请从目录核对。';}render();
  },dispose(){disposed=true;chosenFile=null;draft=blank();root.remove();}};
}
