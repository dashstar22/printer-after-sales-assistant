import {serviceApi as api} from './api.js';
import {el,button,link,field,pairs,orderLabel,orderInfo,trim} from './ui.js';
const fields=['symptom','extra','contact','contactMethod'];
const labels=['故障描述','现场补充信息','联系人','联系方式'];
const limits=[2000,2000,100,200];

export function repairScreen(host,ctx,draftId){
  const root=el('section',null,'repair-layout');host.replaceChildren(root);
  let draft=null,confirmation=null,operation=null,ticket=null,dirty=false,unknown=false,busy=false,orders=[],note='';
  const formValues={orderId:'',symptom:'',extra:'',contact:'',contactMethod:''};
  function hint(patch){return ctx.locator.save({repair:patch});}
  function showDraft(value){draft=value;fields.forEach(k=>formValues[k]=value[k]);formValues.orderId=String(value.orderId);dirty=false;confirmation=null;}
  function render(){
    root.replaceChildren();
    const editor=el('section',null,'workspace-panel');editor.append(el('p','01 / 保存内容','eyebrow'),el('h2','先把现场情况交代清楚。'));
    const form=el('form');form.id='repair-form';form.noValidate=true;
    const select=el('select');select.id='repair-order';select.required=true;select.append(new Option('请选择本人订单',''));
    for(const o of orders)select.append(new Option(orderLabel(o),String(o.id)));select.value=formValues.orderId;
    form.append(field('设备订单',select));
    const info=el('p',orders.find(o=>String(o.id)===formValues.orderId)?orderInfo(orders.find(o=>String(o.id)===formValues.orderId)):'请选择本人订单。','subtle');form.append(info);
    function edited(){dirty=true;confirmation=null;const agree=root.querySelector('#repair-agreed');if(agree)agree.checked=false;sync();}
    select.addEventListener('change',()=>{formValues.orderId=select.value;const o=orders.find(x=>String(x.id)===select.value);info.textContent=o?orderInfo(o):'请选择本人订单。';edited();});
    fields.forEach((k,i)=>{
      const input=el(i<2?'textarea':'input');input.id='repair-'+k;input.value=formValues[k];input.required=true;input.maxLength=limits[i]*2;if(i<2)input.rows=3;
      if(i>=2)input.autocomplete='off';
      input.addEventListener('input',()=>{formValues[k]=input.value;edited();});form.append(field(labels[i]+`（最多 ${limits[i]} 字）`,input));
    });
    const save=el('button','保存草稿','primary-button');save.type='submit';save.id='save-draft';form.append(save);
    form.addEventListener('submit',e=>{e.preventDefault();ctx.run(saveDraft);});editor.append(form);
    const actions=el('div',null,'form-links');
    if(draft)actions.append(button('刷新已保存内容',()=>ctx.run(async()=>{showDraft(await api.get('/api/repair-drafts/'+draft.id));note='已刷新报修内容，请核对后重新确认。';render();})));
    actions.append(link('新建另一份报修','#/customer/repair/new'));editor.append(actions);
    const review=el('section',null,'workspace-panel review-panel');review.append(el('p','02 / 核对并确认','eyebrow'),el('h2','这份内容，将随工单一起保存。'));
    if(note)review.append(el('p',note,'inline-notice'));
    if(draft){
      review.append(el('p',`草稿 ${draft.id} · 已保存版本 ${draft.version}`,'draft-meta'),pairs([['订单 / 型号',orderLabel(draft.order)],['设备信息',orderInfo(draft.order)],...fields.map((k,i)=>[labels[i],draft[k]])]));
    }else review.append(el('p','保存后，在这里核对完整的报修内容。','empty-state'));
    const dirtyNote=el('p',null,'inline-notice');dirtyNote.id='draft-dirty-note';review.append(dirtyNote);
    const label=el('label',null,'check-line');const agree=el('input');agree.type='checkbox';agree.id='repair-agreed';agree.checked=!!confirmation&&!dirty;label.append(agree,document.createTextNode('我已核对以上设备、完整报修内容及联系方式'));review.append(label);
    agree.addEventListener('change',()=>{
      if(!agree.checked){confirmation=null;root.querySelector('#confirmation-meta').textContent='已取消本页确认，请重新核对并确认。';}
      sync();
    });
    const confirm=button('确认以上内容',()=>ctx.run(confirmDraft),'primary-button');confirm.id='confirm-draft';review.append(confirm);
    review.append(el('p','03 / 提交已确认报修','eyebrow submit-label'));
    const meta=el('p',confirmation?`已确认版本 ${confirmation.draftVersion}，有效期至 ${new Date(confirmation.expiresAt).toLocaleString()}。尚未提交。`:'尚未确认，不会创建工单。','subtle');meta.id='confirmation-meta';review.append(meta);
    const execute=button('提交已确认报修',()=>ctx.run(()=>executeOperation(false)),'primary-button');execute.id='execute-repair';review.append(execute);
    if(operation){
      const recovery=el('div',null,'recovery');recovery.append(el('p',unknown?'提交结果待确认。请查询原操作，不要新建报修。':'原操作已保留，可查询已提交结果。'));
      const query=button('查询原操作结果',()=>ctx.run(recover));query.id='recover-operation';recovery.append(query);
      if(!unknown&&!ticket&&confirmation){const retry=button('使用原操作重试提交',()=>ctx.run(()=>executeOperation(true)));retry.id='retry-operation';recovery.append(retry);}
      review.append(recovery);
    }
    if(ticket){
      const result=el('div',null,'success-result');result.id='repair-success';result.append(el('h3','报修已提交'),el('p','工单编号：'+ticket.ticketNo),link('查看工单和处理进度',`#/customer/ticket/${ticket.id}`,'secondary-button'));review.append(result);
    }
    review.append(link('返回售后咨询','#/customer/assistant','fallback-link'));root.append(editor,review);sync();
  }
  function sync(){
    const submitted=!!ticket;
    root.querySelectorAll('#repair-form input,#repair-form textarea,#repair-form select').forEach(c=>c.disabled=busy||unknown||submitted);
    const agreed=root.querySelector('#repair-agreed');if(!agreed)return;
    agreed.disabled=busy||!draft||dirty||unknown||submitted;
    root.querySelector('#draft-dirty-note').textContent=dirty?'内容已修改，请先保存并重新核对确认。':'';
    root.querySelector('#draft-dirty-note').hidden=!dirty;
    root.querySelector('#save-draft').disabled=busy||unknown||submitted;
    root.querySelector('#confirm-draft').disabled=busy||!draft||dirty||!agreed.checked||unknown||submitted||!!confirmation;
    root.querySelector('#execute-repair').disabled=busy||!confirmation||dirty||unknown||submitted;
    root.querySelectorAll('.form-links button').forEach(c=>c.disabled=busy||unknown||submitted);
    root.querySelectorAll('.form-links a').forEach(a=>{a.hidden=unknown;});
  }
  async function saveDraft(){
    const body={orderId:Number(formValues.orderId)};
    if(!body.orderId)throw new Error('请选择本人订单。');
    fields.forEach((k,i)=>{if(!trim(formValues[k])||[...trim(formValues[k])].length>limits[i])throw new Error(`${labels[i]}为必填，去除首尾空白后最多 ${limits[i]} 字。`);body[k]=formValues[k];});
    if(draft)body.expectedVersion=draft.version;
    busy=true;sync();confirmation=null;
    try{
      const saved=await api.write(draft?'/api/repair-drafts/'+draft.id:'/api/repair-drafts',body,draft?'PUT':'POST');
      showDraft(saved);operation=null;ticket=null;hint({draftId:saved.id});note='草稿已保存。请核对右侧内容，再勾选并确认。';
    }catch(e){
      if(e.status===0)note=draft?'暂未确认保存结果。请刷新已保存内容，核对后再操作。':'暂未确认保存结果，请保留填写内容；报修尚未提交。';
      else if(e.status===409)note='报修内容已更新，请刷新已保存内容，核对后再操作。';
      throw e;
    }finally{busy=false;render();}
  }
  async function confirmDraft(){
    if(!draft||dirty||unknown||!root.querySelector('#repair-agreed').checked)return;
    busy=true;sync();
    try{
      const value=await api.write(`/api/repair-drafts/${draft.id}/confirmations`,{confirmed:true,expectedVersion:draft.version});
      // Persist the exact operation before enabling execution. Storage failure never triggers a write.
      operation={draftId:value.draftId,draftVersion:value.draftVersion,confirmationId:value.confirmationId,operationId:value.operationId};
      hint(operation);confirmation=value;note='内容已确认。还需主动点击提交，才会创建工单。';
      if(value.ticketId){ticket=await api.get('/api/tickets/'+value.ticketId);note='该草稿已提交，已读取原工单。';}
    }finally{busy=false;render();}
  }
  async function executeOperation(){
    if(!operation||!confirmation||dirty||unknown||ticket)return;
    hint(operation);unknown=true;busy=true;render();
    try{
      // Same deterministic coordinator as the ordinary form. No model-generated grant or follow-up is required.
      const value=await api.write(`/api/ticket-operations/${operation.operationId}/execute`,{confirmationId:operation.confirmationId,draftId:operation.draftId,expectedVersion:operation.draftVersion});
      ticket=value.ticket;unknown=false;note='报修已提交成功。';
    }finally{busy=false;render();}
  }
  async function recover(){
    if(!operation)return;
    busy=true;sync();
    try{
      let result;
      try{result=await api.get('/api/ticket-operations/'+operation.operationId);}
      catch(e){
        if(e.code!=='CONFIRMATION_CONFLICT')throw e;
        const latest=await api.get('/api/repair-drafts/'+operation.draftId);
        if(latest.version===operation.draftVersion)throw e;
        showDraft(latest);unknown=false;confirmation=null;operation=null;hint({draftId:latest.id});note='内容版本已变化，原确认不再有效，请核对最新内容并重新确认。';return;
      }
      if(result.status==='SUCCEEDED'){ticket=result.ticket;unknown=false;note='已恢复原操作工单，没有再次建单。';}
      else {
        showDraft(await api.get('/api/repair-drafts/'+operation.draftId));unknown=false;
        if(result.status==='EXPIRED'){confirmation=null;operation=null;hint({draftId:draft.id});note='原确认已过期且未建单，请重新核对并确认。';}
        else{confirmation=result;note='原操作尚未建单。可主动使用原操作重试提交。';}
      }
    }finally{busy=false;render();}
  }
  return {async open(){
    busy=true;render();
    try{
      let page=1,value;do{value=await api.get('/api/orders?page='+page+'&size=100');orders.push(...value.items);page++;}while(page<=value.totalPages);
      const locator=ctx.locator.read();
      const handoff=draftId==='new'?locator.repairHandoff:null;
      const stored=draftId==='new'?null:locator.repair;
      if(draftId==='new'){
        hint(null);ctx.locator.save({repairHandoff:null});draftId=null;
        // Consume the new-draft action once. Reload must recover the current operation.
        history.replaceState(null,'','#/customer/repair');
      }
      const id=draftId||stored?.draftId;
      if(id){
        showDraft(await api.get('/api/repair-drafts/'+encodeURIComponent(id)));
        if(stored?.operationId&&Number(id)===stored.draftId){operation=stored;unknown=true;await recover();}
        else hint({draftId:draft.id});
      }else{
        formValues.orderId=String(orders.find(o=>o.id===locator.selectedOrderId)?.id||orders[0]?.id||'');
        ctx.locator.save({selectedOrderId:null});
        // Only identifiers cross pages; read the user's literal message again under server ownership checks.
        if(handoff && /^[a-f0-9-]{36}$/.test(handoff.conversationId) && /^[a-f0-9-]{36}$/.test(handoff.turnId)){
          try{
            let conversation=await api.get(`/api/conversations/${handoff.conversationId}?page=1&size=100`);
            if(orders.some(o=>o.id===conversation.orderId)){
              let source=conversation.messages.items.find(m=>m.role==='USER'&&m.turnId===handoff.turnId);
              for(let p=2;!source&&p<=conversation.messages.totalPages;p++){
                const batch=await api.get(`/api/conversations/${handoff.conversationId}?page=${p}&size=100`);
                source=batch.messages.items.find(m=>m.role==='USER'&&m.turnId===handoff.turnId);
              }
              if(source && [...source.content].length<=limits[0]){
                formValues.orderId=String(conversation.orderId);formValues.symptom=source.content;dirty=true;
              }
            }
          }catch(error){if(error.status!==404&&error.status!==400)throw error;}
        }
      }
    }finally{busy=false;render();}
  },dispose(){root.remove();}};
}
