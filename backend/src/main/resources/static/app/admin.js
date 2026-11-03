import {serviceApi as api, ApiError} from './api.js';
import {el,button,link,field,pairs,userLabel} from './ui.js';

const roles={ADMIN:'管理员',STAFF:'客服',CUSTOMER:'客户'};
const accountStates={ACTIVE:'正常',DISABLED:'已停用'};
const base='/api/admin';
const uuid=()=>crypto.randomUUID();
function input(id,type='text',maxLength){const node=el('input');node.id=id;node.name=id;node.type=type;node.required=true;if(maxLength)node.maxLength=maxLength;return node;}
function option(value,text){const node=el('option',text);node.value=value;return node;}
function actions(...nodes){const box=el('div',null,'admin-actions');box.append(...nodes);return box;}
function notice(text){return el('p',text,'admin-note');}
function accountCell(row){
  const cell=el('div',null,'account-summary');
  const email=row.displayEmail||row.email;
  cell.append(el('strong',email||'尚未绑定邮箱'));
  if(row.displayName)cell.append(el('span',row.displayName,'account-detail'));
  else if(!email&&!/^email_[a-f0-9]{32}$/i.test(row.username||''))cell.append(el('span','原用户名：'+row.username,'account-detail'));
  cell.append(el('small','账号编号：'+row.id,'account-detail'));
  return cell;
}
function table(headers,rows){
  const wrap=el('div',null,'admin-table-wrap');wrap.tabIndex=0;wrap.setAttribute('role','region');wrap.setAttribute('aria-label',headers.join('、'));
  const t=el('table',null,'admin-table'),head=el('thead'),tr=el('tr');headers.forEach(h=>{const th=el('th',h);th.scope='col';tr.append(th);});head.append(tr);
  const body=el('tbody');rows.forEach(row=>{const tr=el('tr');row.forEach(value=>{const td=el('td');td.append(value instanceof Node?value:document.createTextNode(String(value??'—')));tr.append(td);});body.append(tr);});t.append(head,body);wrap.append(t);return wrap;
}
// A retry of unchanged input uses its original identity. Changed input is a new operation.
function writeIdentity(){let previous='',requestId='';return body=>{const fingerprint=JSON.stringify(body);if(previous!==fingerprint){previous=fingerprint;requestId=uuid();}return {...body,requestId};};}
async function uploadCsv(file,requestId){
  const csrf=await api.get('/api/auth/csrf'),data=new FormData();data.append('file',file);data.append('requestId',requestId);
  const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),30000);
  try{
    const response=await fetch(base+'/order-imports',{method:'POST',body:data,headers:{Accept:'application/json',[csrf.headerName]:csrf.token},credentials:'same-origin',cache:'no-store',signal:controller.signal});
    const value=await response.json();if(!response.ok)throw new ApiError(response.status,value.code,value.message,value.traceId);return value;
  }catch(error){if(error instanceof ApiError)throw error;throw new ApiError(0,'NETWORK_UNKNOWN','预检结果暂未确认。请保留当前文件，用原请求重试预检。');}
  finally{clearTimeout(timeout);}
}

export function administrationScreen(host,ctx,section='accounts'){
  const root=el('div',null,'administration');host.replaceChildren(root);let alive=true,cursor=0,history=[];
  const summary=el('p',null,'admin-feedback');summary.setAttribute('role','status');
  const tabs=el('nav',null,'admin-tabs');tabs.setAttribute('aria-label','基础管理');
  for(const [path,text]of [['accounts','账号状态'],['invitations','客服邀请'],['models','型号与别名'],['orders','设备订单'],['imports','CSV 导入']]){const a=link(text,'#/admin/'+path);if(path===section)a.setAttribute('aria-current','page');tabs.append(a);}
  const list=el('section',null,'workspace-panel'),editor=el('section',null,'workspace-panel');root.append(tabs,summary,list,editor);
  function tell(message){if(alive)summary.textContent=message;}
  function finish(message){tell(message);}
  const run=action=>ctx.run(async()=>{tell('');await action();});
  function pager(data,load){
    const bar=actions();if(history.length)bar.append(button('上一页',()=>run(async()=>{cursor=history.pop();await load();})));
    if(data.nextCursor!==null&&data.nextCursor!==undefined)bar.append(button('下一页',()=>run(async()=>{history.push(cursor);cursor=data.nextCursor;await load();})));
    if(bar.childElementCount)list.append(bar);
  }
  function heading(text,refresh){const h=el('div',null,'panel-heading');h.append(el('h2',text));if(refresh)h.append(button('刷新列表',()=>run(refresh)));return h;}
  function submit(form,text,handler){const b=el('button',text,'primary-button');b.type='submit';form.append(actions(b));form.addEventListener('submit',event=>{event.preventDefault();if(form.reportValidity())run(handler);});return b;}
  async function loadAccounts(){
    const data=await api.get(`${base}/accounts?cursor=${cursor}&pageSize=20`);if(!alive)return;
    list.replaceChildren(heading('账号状态',loadAccounts),notice('客户自行验证邮箱注册；客服通过邮箱邀请开通。停用会撤销该账号的旧登录凭证。'));
    list.append(table(['登录邮箱 / 姓名','角色','状态','邮箱验证','操作'],data.items.map(row=>[
      accountCell(row),roles[row.role]||row.role,accountStates[row.status]||row.status,
      row.emailVerifiedAt?'已验证':'待本人绑定',button('维护状态',()=>accountEditor(row))
    ])));if(!data.items.length)list.append(notice('暂无账号。'));pager(data,loadAccounts);
  }
  function accountEditor(row){
    const form=el('form'),status=el('select');status.id='account-status';status.append(option('ACTIVE','正常'),option('DISABLED','停用'));status.value=row.status;
    const identified=writeIdentity();editor.replaceChildren(el('h2','维护账号状态'),pairs([['账号',userLabel(row)],['登录邮箱',row.displayEmail||row.email||'尚未绑定邮箱'],['账号编号',row.id],['角色',roles[row.role]],['当前版本',row.version]]),notice('请核对目标账号。最后有效管理员受保护；如停用当前账号，需要重新登录。'));
    form.append(field('目标状态',status));submit(form,'确认更改状态',async()=>{
      const result=await api.write(`${base}/accounts/${row.id}`,identified({status:status.value,expectedVersion:row.version}),'PATCH');
      if(!alive)return;accountEditor(result);await loadAccounts();finish('账号状态已更新，旧凭证已撤销。');
    });editor.append(form,link('邀请新客服 →','#/admin/invitations','inline-link'),notice('账号遗失密码时使用本人邮箱找回；未绑定或失去邮箱控制时，由负责人核实身份后按恢复说明处理。'));
  }
  async function loadModels(){
    const data=await api.get(`${base}/models?cursor=${cursor}&pageSize=20`);if(!alive)return;
    list.replaceChildren(heading('型号与审核别名',loadModels),notice('只登记已核实的标准型号和别名。相似型号不会自动合并，演示来源不能改作真实资料。'));
    list.append(table(['标准型号','品牌','来源','版本','操作'],data.items.map(row=>[row.modelCode,row.brand,row.isDemo?'演示':'正式登记',row.version,button('编辑型号',()=>run(async()=>{const detail=await api.get(`${base}/models/${row.id}`);if(alive)modelEditor(detail);} ))])));
    list.append(actions(button('登记新型号',()=>modelEditor())));pager(data,loadModels);
  }
  function modelEditor(row){
    const form=el('form'),code=input('model-code','text',64),brand=input('model-brand','text',64),aliases=el('textarea'),demo=el('select'),identified=writeIdentity();
    aliases.id='model-aliases';aliases.rows=4;aliases.maxLength=3300;demo.id='model-demo';demo.append(option('false','正式登记'),option('true','演示'));
    if(row){code.value=row.modelCode;brand.value=row.brand;aliases.value=(row.aliases||[]).map(a=>a.originalAlias).filter(a=>a!==row.modelCode).join('\n');demo.value=String(row.isDemo);demo.disabled=true;}
    editor.replaceChildren(el('h2',row?'编辑型号':'登记新型号'));
    form.append(field('标准型号',code),field('品牌',brand),field('审核别名（每行一项，最多 50 项，不重复填写标准型号）',aliases),field('来源',demo));
    if(row)form.append(notice('已被订单或资料引用的标准型号不可修改；来源标记保持不变。'));
    submit(form,row?'保存型号':'登记型号',async()=>{
      const body={modelCode:code.value.trim(),brand:brand.value.trim(),aliases:aliases.value.split(/\r?\n/).map(a=>a.trim()).filter(Boolean),isDemo:demo.value==='true'};
      if(body.aliases.length>50){tell('别名最多 50 项，请删减后保存。');return;}if(row)body.expectedVersion=row.version;
      const result=await api.write(base+'/models'+(row?'/'+row.id:''),identified(body),row?'PATCH':'POST');
      if(!alive)return;modelEditor(result);await loadModels();finish('型号已保存。');
    });editor.append(form);
  }
  async function loadOrders(){
    const data=await api.get(`${base}/orders?cursor=${cursor}&pageSize=20`);if(!alive)return;
    list.replaceChildren(heading('客户设备订单',loadOrders),notice('由管理员核实客户身份后登记设备归属。客户知道订单号或序列号不代表拥有设备。'));
    list.append(table(['订单 / 序列号','客户 ID / 型号 ID','来源 / 状态','核实记录','操作'],data.items.map(row=>[
      `${row.orderNo} / ${row.serialNo}`,`${row.customerId} / ${row.modelId}`,`${row.isDemo?'演示':'实际业务'} / ${row.status==='ACTIVE'?'有效':'已作废'}`,
      `${row.verificationNote||'—'}；登记人 ${row.verifiedBy??'—'}；${row.verifiedAt??'—'}`,
      actions(button('查看 / 更正',()=>run(async()=>{const detail=await api.get(`${base}/orders/${row.id}`);if(alive)await orderEditor(detail);})),...(row.status==='ACTIVE'?[button('作废登记',()=>voidEditor(row))]:[]))
    ])));list.append(actions(button('登记客户设备',()=>run(()=>orderEditor()))));pager(data,loadOrders);
  }
  async function orderEditor(row){
    const form=el('form'),orderNo=input('order-number','text',64),customer=input('order-customer','number'),model=el('select'),serial=input('order-serial','text',128),date=input('order-date','date'),demo=el('select'),note=el('textarea'),identified=writeIdentity();
    customer.min='1';customer.step='1';model.id='order-model';model.required=true;model.append(option('','请选择已登记型号'));demo.id='order-demo';demo.append(option('false','实际业务'),option('true','演示'));note.id='order-verification';note.required=true;note.maxLength=500;note.rows=3;
    let next=0;do{const page=await api.get(`${base}/models?cursor=${next}&pageSize=100`);if(!alive)return;for(const m of page.items)model.append(option(String(m.id),`${m.modelCode} · ${m.brand}${m.isDemo?'（演示型号）':''}`));next=page.nextCursor;}while(next!==null&&next!==undefined);
    if(row){orderNo.value=row.orderNo;customer.value=row.customerId;model.value=String(row.modelId);serial.value=row.serialNo;date.value=row.purchaseDate;demo.value=String(row.isDemo);demo.disabled=true;note.value=row.verificationNote||'';}
    editor.replaceChildren(el('h2',row?'查看 / 更正设备订单':'登记客户设备'),notice('先在账号状态页核对客户内部 ID，再记录本次身份和购买记录核实依据。不要填写密码或身份证件全文。'));
    form.append(field('订单编号',orderNo),field('客户内部 ID',customer),field('标准型号',model),field('设备序列号',serial),field('购买日期',date),field('业务来源',demo),field('身份与购买记录核实说明',note));
    if(row){editor.append(pairs([['订单 ID',row.id],['登记人 ID',row.verifiedBy],['核实时间',row.verifiedAt],['导入批次',row.importId],['版本',row.version],['状态',row.status==='ACTIVE'?'有效':'已作废'],['作废原因',row.voidReason]]));form.append(notice('已被咨询、草稿或工单引用的订单不能直接更正归属；录错时作废原记录后重新登记，历史记录保留。'));}
    const save=submit(form,row?'保存更正':'核实并登记设备',async()=>{
      const body={orderNo:orderNo.value.trim(),customerId:Number(customer.value),modelId:Number(model.value),serialNo:serial.value.trim(),purchaseDate:date.value,isDemo:demo.value==='true',verificationNote:note.value.trim()};if(row)body.expectedVersion=row.version;
      const result=await api.write(base+'/orders'+(row?'/'+row.id:''),identified(body),row?'PATCH':'POST');if(!alive)return;
      await orderEditor(result);await loadOrders();finish('设备订单已保存，对应客户可刷新查回。');
    });if(row?.status!=='ACTIVE'&&row){save.disabled=true;for(const control of form.elements)control.disabled=true;}
    editor.append(form);
  }
  function voidEditor(row){
    const form=el('form'),reason=el('textarea'),identified=writeIdentity();reason.id='order-void-reason';reason.required=true;reason.maxLength=500;reason.rows=3;
    editor.replaceChildren(el('h2','作废订单 '+row.orderNo),notice('作废会保留原归属、咨询和工单历史。若需纠正设备归属，请在作废后另行登记正确记录。'));
    form.append(field('作废原因',reason));submit(form,'确认作废订单',async()=>{await api.write(`${base}/orders/${row.id}/void`,identified({reason:reason.value.trim(),expectedVersion:row.version}));if(!alive)return;editor.replaceChildren(el('h2','订单已作废'),actions(button('重新登记正确设备',()=>run(()=>orderEditor()))));await loadOrders();finish('已作废原订单，历史记录保持。');});editor.append(form);
  }
  const importKey=`paper-order-import:${ctx.user.id}`;
  let snapshot=null,selectedFile=null,previewId='',commitBody=null;
  function savedImport(){try{return sessionStorage.getItem(importKey);}catch{return null;}}
  function saveImport(id){try{sessionStorage.setItem(importKey,id);}catch{/* A denied storage permission leaves manual recovery available. */}}
  async function importEditor(){
    list.replaceChildren(heading('CSV 设备订单导入'),notice('使用 UTF-8 CSV，最多 1MB、500 行。先预检，再核对快照并确认整批录入。模板不包含密码。'),link('下载 CSV 模板',base+'/order-imports/template','secondary-button'));
    const fields=el('p','列：orderNo、customerId、modelCode、serialNo、purchaseDate、isDemo、verificationNote。isDemo 填 true 或 false；购买日期格式 YYYY-MM-DD；客户须为已有有效客户，型号须为已登记标准型号或审核别名。','admin-note');list.append(fields);
    const form=el('form'),file=input('order-import-file','file');file.accept='.csv,text/csv';
    const detail=el('div');editor.replaceChildren(el('h2','预检文件'),form,detail);form.append(field('选择 CSV 文件',file));
    file.addEventListener('change',()=>{selectedFile=file.files[0]||null;previewId=uuid();snapshot=null;commitBody=null;detail.replaceChildren(notice('文件已变更，请重新预检。'));});
    submit(form,'预检 CSV',async()=>{
      const chosen=file.files[0];if(!chosen)return;if(chosen.size>1024*1024){tell('CSV 不能超过 1MB。');return;}
      if(chosen!==selectedFile){selectedFile=chosen;previewId=uuid();}if(!previewId)previewId=uuid();
      snapshot=await uploadCsv(chosen,previewId);if(!alive)return;commitBody=null;saveImport(snapshot.importId);renderSnapshot(detail);finish('预检完成。请核对下方快照和错误行。');
    });
    const recoverForm=el('form'),recover=input('order-import-recover','text',36);recover.required=true;recover.value=savedImport()||'';recover.pattern='[0-9a-fA-F-]{36}';recoverForm.append(field('回查本人预检批次 ID',recover));
    submit(recoverForm,'回查批次',async()=>{snapshot=await api.get(`${base}/order-imports/${encodeURIComponent(recover.value.trim())}`);if(!alive)return;saveImport(snapshot.importId);commitBody=null;renderSnapshot(detail);});list.append(recoverForm);
    if(recover.value){snapshot=await api.get(`${base}/order-imports/${encodeURIComponent(recover.value)}`);if(alive)renderSnapshot(detail);}
  }
  function renderSnapshot(detail){
    if(!snapshot)return;const current=snapshot;detail.replaceChildren(el('h3','预检快照'),pairs([['批次 ID',current.importId],['状态',current.status==='COMMITTED'?'已整批录入':'待确认'],['到期时间',current.expiresAt],['有效行数',current.rows?.length??current.result?.count??0],['错误行数',current.errors?.length??0]]));
    if(current.status==='COMMITTED'){detail.append(notice(`已成功录入 ${current.result?.count??0} 条订单。刷新回查不会重复录入。`));return;}
    if(current.errors?.length)detail.append(table(['错误行号','原因'],current.errors.map(error=>[error.row,error.message])));
    if(current.rows?.length)detail.append(table(['订单编号','客户 ID','型号输入','序列号','购买日期','来源','核实说明'],current.rows.map(row=>[row.orderNo,row.customerId,row.modelInput,row.serialNo,row.purchaseDate,row.isDemo?'演示':'实际业务',row.verificationNote])));
    if(current.errors?.length){detail.append(notice('存在错误行，不能提交。请修正原文件后重新预检。'));return;}
    if(Date.parse(current.expiresAt)<=Date.now()){detail.append(notice('预检已到期，请重新选择文件预检。'));return;}
    const confirm=button('核对快照并确认整批录入',()=>run(async()=>{
      if(!commitBody)commitBody={inputHash:current.inputHash,expectedVersion:current.version,requestId:uuid()};
      const result=await api.write(`${base}/order-imports/${current.importId}/commit`,commitBody);if(!alive)return;
      snapshot=await api.get(`${base}/order-imports/${current.importId}`);if(!alive)return;renderSnapshot(detail);finish(`已整批录入 ${result.count} 条订单。`);
    }),'primary-button');detail.append(notice('确认的是上面已预检的固定快照。更换文件后必须重新预检；提交无响应时先回查本批次。'),actions(confirm,button('回查本批次结果',()=>run(async()=>{snapshot=await api.get(`${base}/order-imports/${current.importId}`);if(alive)renderSnapshot(detail);} ))));
  }
  return {async open(){
    if(ctx.user.role!=='ADMIN'){list.replaceChildren(el('p','只有管理员可以访问基础管理。'));editor.hidden=true;return;}
    if(section==='accounts'){await loadAccounts();editor.append(el('h2','账号维护'),notice('在列表选择账号维护状态，或发送客服邮箱邀请。'),link('管理客服邀请 →','#/admin/invitations','secondary-button'));}
    else if(section==='models'){await loadModels();if(alive)modelEditor();}
    else if(section==='orders'){await loadOrders();if(alive)await orderEditor();}
    else if(section==='imports')await importEditor();
    else {list.append(el('p','请选择账号状态、型号、设备订单或导入。'));editor.hidden=true;}
  },dispose(){alive=false;root.remove();}};
}
