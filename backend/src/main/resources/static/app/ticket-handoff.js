import {serviceApi as api} from './api.js';
import {el,button,field,pairs,trim,userLabel} from './ui.js';

export function ticketIdentityFailure(error){
  return error.status===401||error.status===403&&['EMAIL_BIND_REQUIRED','EMAIL_BINDING_REQUIRED','PASSWORD_CHANGE_REQUIRED','PASSWORD_AUTHENTICATION_REQUIRED','PASSWORD_LOGIN_REQUIRED'].includes(error.code);
}

// A pending mutation keeps its original input/version. Reloading never resends it.
export function createTicketHandoff(ctx,id,refresh,notify){
  const key=`printer.service.ticket-request.v1.${ctx.user.id}.${id}`;
  let photos=[],context={items:[]},staff=[],pending=null,files=[],text='',reason='',target='',working=false,contextError='',photoError='',contextOpen=false,disposed=false;
  try{pending=JSON.parse(sessionStorage.getItem(key));if(!pending||!['supplements','attachments','transfer'].includes(pending.action))pending=null;}catch{/* No trusted data comes from storage. */}
  if(pending?.action==='supplements')text=pending.body.text||'';
  if(pending?.action==='transfer'){reason=pending.body.reason||'';target=String(pending.body.targetStaffId||'');}
  function save(){try{pending?sessionStorage.setItem(key,JSON.stringify(pending)):sessionStorage.removeItem(key);}catch{/* In-memory recovery still works. */}}
  async function load(){
    if(disposed)return;
    // Read detail first in the caller: inaccessible tickets must never render stale resources.
    const result=await Promise.allSettled([api.get(`/api/tickets/${id}/attachments`),api.get(`/api/tickets/${id}/conversation-context?pageSize=20`)]);
    if(disposed)return;
    // Identity gates invalidate the whole screen, even when the parallel detail read succeeded.
    for(const item of result)if(item.status==='rejected'&&ticketIdentityFailure(item.reason))throw item.reason;
    if(result[0].status==='fulfilled'){photos=result[0].value.items;photoError='';}else{photos=[];photoError='暂时无法读取照片，请刷新重试。';}
    if(result[1].status==='fulfilled'){context=result[1].value;contextError='';}else{context={items:[]};contextError='暂时无法读取关联咨询，请刷新重试。';}
    if(ctx.user.role==='ADMIN'){
      staff=[];let cursor=0;
      do{const page=await api.get(`/api/admin/accounts?cursor=${cursor}&pageSize=100`);if(disposed)return;staff.push(...page.items.filter(u=>u.role==='STAFF'&&u.status==='ACTIVE'&&!u.mustChangePassword&&u.emailVerifiedAt));cursor=page.nextCursor;}while(cursor);
    }
  }
  async function hashes(input){return Promise.all(input.map(async file=>({name:file.name,size:file.size,type:file.type,hash:[...new Uint8Array(await crypto.subtle.digest('SHA-256',await file.arrayBuffer()))].map(b=>b.toString(16).padStart(2,'0')).join('')})));}
  function failValidation(message){throw new Error(message);}
  async function submit(action,ticket,retry=false){
    if(disposed||working||pending&&!retry)return;
    if(!retry){
      if(action==='supplements'&&(!trim(text)||[...trim(text)].length>2000))failValidation('请填写补充内容，去除首尾空白后最多 2000 字。');
      if(action==='transfer'&&(!target||!trim(reason)||[...trim(reason)].length>500))failValidation('请选择有效客服并填写转交原因，最多 500 字。');
      if(action==='attachments'&&(!files.length||files.length>3||files.some(f=>f.size>5*1024*1024||!['image/jpeg','image/png'].includes(f.type))))failValidation('请选择 1～3 张 JPEG/PNG 照片，每张最多 5MB。');
      const body={expectedVersion:ticket.version,requestId:crypto.randomUUID()};
      if(action==='supplements')body.text=text;
      if(action==='transfer')Object.assign(body,{targetStaffId:Number(target),reason});
      const next={action,body};if(action==='attachments')next.files=await hashes(files);if(disposed)return;pending=next;save();
    }
    if(!pending)return;
    if(pending.action==='attachments'){
      if(JSON.stringify(await hashes(files))!==JSON.stringify(pending.files))failValidation('请重新选择原照片（相同文件、顺序和内容）后使用原请求重试。');
    }
    if(disposed)return;
    working=true;let confirmed=false;
    try{
      const {action,body}=pending;
      if(action==='attachments'){
        const form=new FormData();form.append('expectedVersion',String(body.expectedVersion));form.append('requestId',body.requestId);for(const file of files)form.append('files',file);
        await api.upload(`/api/tickets/${id}/attachments`,form);
      }else await api.write(action==='transfer'?`/api/staff/tickets/${id}/transfer`:`/api/tickets/${id}/supplements`,body);
      confirmed=true;pending=null;save();text='';reason='';target='';files=[];
      if(!disposed){notify(action==='transfer'?'负责人已转交。':action==='attachments'?'故障照片已保存。':'补充内容已保存。');await refresh();}
    }catch(error){
      if(disposed)throw error;
      if(confirmed)notify('操作已保存，但暂时无法读取最新状态。请刷新查回。');
      else if(error.status>0&&error.status<500){pending=null;save();notify('本次请求未成功，输入已保留。请核对最新状态后重新提交。');}
      else notify('提交结果待核对，原输入与请求标识已保留。请先刷新，再按需要重试原请求。');
      throw error;
    }finally{working=false;}
  }
  function render(host,ticket){
    if(disposed)return;
    const closed=ticket.status==='CLOSED',locked=working||!!pending;
    const area=el('section',null,'ticket-handoff');host.append(area);
    const consultant=el('details',null,'ticket-context');consultant.id='ticket-context';consultant.open=contextOpen;consultant.addEventListener('toggle',()=>{contextOpen=consultant.open;});consultant.append(el('summary','关联咨询（报修时的记录）'));
    consultant.append(el('p','只展示创建工单时关联的咨询，后续现场变化请查看工单补充。','subtle'));
    const log=el('ol',null,'ticket-context-messages');
    function appendMessages(items){for(const item of items){const row=el('li');row.append(el('strong',item.role==='USER'?'客户':'售后助手'),el('p',item.content,'source-content'),el('time',new Date(item.createdAt).toLocaleString(),'subtle'));
      for(const citation of item.response?.citations||[]){const detail=el('details',null,'citation');detail.append(el('summary',`${citation.title||'引用资料'} · ${citation.simulated?'模拟资料':'来源资料'}`),pairs([['版本',citation.version],['定位',citation.pageNumber?'第 '+citation.pageNumber+' 页':citation.section],['来源',citation.source]]),el('p',citation.content,'source-content'));row.append(detail);}log.append(row);}}
    if(contextError)consultant.append(el('p',contextError,'inline-notice'));else if(!context.items.length)consultant.append(el('p','这张工单没有关联咨询记录。','empty-state'));else appendMessages(context.items);
    consultant.append(log);
    if(context.nextCursor){const more=button('查看更多关联消息',()=>ctx.run(async()=>{if(disposed)return;const page=await api.get(`/api/tickets/${id}/conversation-context?cursor=${context.nextCursor}&pageSize=20`);if(disposed)return;context={...context,items:[...context.items,...page.items],nextCursor:page.nextCursor};await refresh(false);}));consultant.append(more);}
    area.append(consultant);
    const gallery=el('section',null,'ticket-photos');gallery.append(el('h3',`故障照片（${photos.length}/10）`));
    if(photoError)gallery.append(el('p',photoError,'inline-notice'));else if(!photos.length)gallery.append(el('p','尚未补充故障照片。','subtle'));
    const grid=el('div',null,'ticket-photo-grid');
    for(const photo of photos){
      // Construct only the authorized same-origin URL; never trust a URL from an API response.
      if(!Number.isSafeInteger(Number(photo.id))||Number(photo.id)<1)continue;
      const figure=el('figure');const img=el('img');img.src=`/api/tickets/${encodeURIComponent(id)}/attachments/${Number(photo.id)}`;img.alt=`故障照片 ${photo.id}`;img.loading='lazy';img.referrerPolicy='no-referrer';
      img.addEventListener('error',()=>{img.hidden=true;figure.append(el('p','照片暂时无法读取，请刷新页面重试。','inline-notice'));},{once:true});
      const caption=el('figcaption',`${photo.width} × ${photo.height} · ${Math.ceil(photo.bytes/1024)} KB`);const open=el('a','查看原图');open.href=img.src;open.target='_blank';open.rel='noopener noreferrer';figure.append(img,caption,open);grid.append(figure);
    }gallery.append(grid);area.append(gallery);
    if(pending){
      const recovery=el('section',null,'recovery');recovery.id='ticket-request-recovery';recovery.append(el('h3','上次提交待核对'),el('p','页面刷新不会自动重发。请先查看最新时间线和照片；仍需确认时，用原请求重试，系统会核对原标识。'),el('p',`请求标识：${pending.body.requestId}`,'subtle'));
      if(pending.action==='attachments'){
        const input=el('input');input.type='file';input.id='retry-ticket-photos';input.accept='image/jpeg,image/png';input.multiple=true;input.addEventListener('change',()=>{files=[...input.files];});recovery.append(field('重新选择原照片（顺序和内容保持一致）',input),el('p',(pending.files||[]).map(f=>f.name).join('、'),'subtle'));
      }else recovery.append(el('p',pending.body.text||pending.body.reason,'source-content'));
      const retry=button('使用原请求重试',()=>ctx.run(async()=>{try{await submit(pending.action,ticket,true);}finally{await refresh(false);}}));retry.id='retry-ticket-request';retry.disabled=working;recovery.append(retry);
      recovery.append(button('已核对，放弃原请求',()=>ctx.run(async()=>{pending=null;save();notify('已放弃恢复原请求。请核对时间线，避免再次提交已保存内容。');await refresh();})));area.append(recovery);
    }
    if(ctx.user.role==='CUSTOMER'&&!closed){
      const form=el('form');form.id='ticket-supplement-form';const input=el('textarea');input.id='ticket-supplement';input.rows=4;input.maxLength=4000;input.value=text;input.disabled=locked;input.required=true;input.addEventListener('input',()=>{text=input.value;});
      form.append(el('h3','补充现场情况'),field('补充内容（最多 2000 字）',input),el('p','补充会保留在时间线中，请勿重复提交。这不是实时聊天。','subtle'));
      const submitButton=el('button','保存补充内容','primary-button');submitButton.type='submit';submitButton.id='submit-ticket-supplement';submitButton.disabled=locked;form.append(submitButton);form.addEventListener('submit',event=>{event.preventDefault();ctx.run(async()=>{try{await submit('supplements',ticket);}finally{await refresh(false);}});});area.append(form);
      const upload=el('form');upload.id='ticket-photo-form';const inputFiles=el('input');inputFiles.id='ticket-photo-files';inputFiles.type='file';inputFiles.accept='image/jpeg,image/png';inputFiles.multiple=true;inputFiles.required=!files.length;inputFiles.disabled=locked||photos.length>=10;inputFiles.addEventListener('change',()=>{files=[...inputFiles.files];});
      upload.append(el('h3','补充故障照片'),field('选择照片',inputFiles),el('p','仅 JPEG/PNG，每次最多 3 张，每张最多 5MB，每工单最多 10 张。照片仅供人工查看。','subtle'));
      if(files.length)upload.append(el('p','已选：'+files.map(f=>f.name).join('、'),'subtle'));
      const uploadButton=el('button','上传故障照片','primary-button');uploadButton.type='submit';uploadButton.id='upload-ticket-photos';uploadButton.disabled=inputFiles.disabled;upload.append(uploadButton);upload.addEventListener('submit',event=>{event.preventDefault();ctx.run(async()=>{try{await submit('attachments',ticket);}finally{await refresh(false);}});});area.append(upload);
    }else if(closed)area.append(el('p','工单已关闭，补充、照片和处理记录只读。新的问题请发起新报修。','inline-notice'));
    if(ctx.user.role==='ADMIN'&&ticket.status==='IN_PROGRESS'){
      const form=el('form');form.id='ticket-transfer-form';const select=el('select');select.id='ticket-transfer-target';select.required=true;select.disabled=locked;select.append(new Option('请选择有效客服',''));for(const user of staff.filter(u=>u.id!==ticket.acceptedBy))select.append(new Option(`${userLabel(user)} · 账号 ${user.id}`,String(user.id)));select.value=target;select.addEventListener('change',()=>{target=select.value;});
      const input=el('textarea');input.id='ticket-transfer-reason';input.rows=3;input.maxLength=1000;input.required=true;input.disabled=locked;input.value=reason;input.addEventListener('input',()=>{reason=input.value;});
      const submitButton=el('button','确认转交负责人','primary-button');submitButton.type='submit';submitButton.id='transfer-ticket';submitButton.disabled=locked||!staff.some(u=>u.id!==ticket.acceptedBy);form.append(el('h3','转交负责人'),field('接手客服',select),field('转交原因（最多 500 字）',input),submitButton);
      if(!staff.some(u=>u.id!==ticket.acceptedBy))form.append(el('p','暂无其他有效客服，请先在账号管理中核对客服状态。','subtle'));form.addEventListener('submit',event=>{event.preventDefault();ctx.run(async()=>{try{await submit('transfer',ticket);}finally{await refresh(false);}});});area.append(form);
    }
  }
  return {load,render,dispose(){disposed=true;files=[];photos=[];context={items:[]};staff=[];}};
}
