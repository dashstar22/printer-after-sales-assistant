import {serviceApi as api} from './api.js';
import {el, button, link, field, orderLabel, orderInfo, trim} from './ui.js';
import {answerStatus,answerDetails,mayReport,createAnswerFeedback} from './answer-view.js';

const statusNames = {ANSWERED:'已返回回复', NO_EVIDENCE:'无法据资料确认', KNOWLEDGE_UNAVAILABLE:'资料暂时不可查询',
  NEEDS_INPUT:'请补充信息', RETRIEVAL_UNAVAILABLE:'资料暂时不可查询', RESULT_UNKNOWN:'助手结果待核对',
  KNOWLEDGE_TIMEOUT:'资料查询超时', EVIDENCE_NOT_AUTHORIZED:'资料尚未获准用于对话', REFERENCE_REJECTED:'引用未通过核对',
  MISSING_INFORMATION:'请补充报修信息', MISSING_FIELDS:'请补充报修信息', DRAFT_PREPARED:'已准备草稿，尚未提交',
  TICKET_CREATED:'已创建工单', ASSISTANT_NOT_CONNECTED:'助手未启用，消息已保存', PROCESSING:'正在处理',
  MODEL_UNAVAILABLE:'模型暂时不可用', MODEL_TIMEOUT:'模型响应超时', TURN_TIMEOUT:'本轮等待超时', WRITE_RESULT_UNKNOWN:'写入结果待核对'};
export function conversationScreen(host, ctx) {
  let detail = null, currentPage = 1, size = 20, selectedOrder = null, pendingTurn = null, busy = false;
  let orders = [], messageText = '', note = '', epoch = 0, logResize = null, disposed = false;
  const root = el('section', null, 'assistant-layout'); host.replaceChildren(root);
  const answerFeedback=createAnswerFeedback({userId:ctx.user.id,isActive:()=>!disposed,onAuthError:ctx.onAuthError});
  function handoff(turnId){ctx.locator.save({repairHandoff:detail&&turnId?{conversationId:detail.id,turnId}:null});}

  function render() {
    if(disposed)return;
    logResize?.disconnect();
    root.replaceChildren(); root.setAttribute('aria-busy',String(busy));
    const aside = el('aside', null, 'workspace-panel device-panel');
    aside.append(el('h2','本次咨询设备'));
    const select = el('select'); select.id = 'assistant-order'; select.disabled = busy || !!pendingTurn;
    select.append(new Option('请选择本人订单', ''));
    for (const order of orders) select.append(new Option(orderLabel(order),String(order.id)));
    select.value = String(selectedOrder || '');
    select.addEventListener('change',()=>{selectedOrder=Number(select.value)||null;render();});
    aside.append(field('设备订单',select));
    const selected = orders.find(o=>o.id===selectedOrder);
    const deviceDetails=el('details',null,'device-context');deviceDetails.append(el('summary','设备详情'));
    if(selected)deviceDetails.append(el('p',orderInfo(selected),'subtle'));
    deviceDetails.append(el('p','更换设备将开启新咨询，原消息保留在原咨询中。','subtle'));aside.append(deviceDetails);
    const start=button(detail ? '为此设备新建咨询' : '开始咨询',()=>ctx.run(async()=>{
      if(!selectedOrder || pendingTurn)return;
      // New empty conversation has no business side effects. Existing context is never reused for another order.
      const created=await api.write('/api/conversations',{orderId:selectedOrder});
      if(disposed)return;ctx.locator.save({conversationId:created.id});detail=created;currentPage=1;messageText='';note='';render();
    }),'primary-button'); start.id='new-conversation';start.disabled=busy||!selectedOrder||!!pendingTurn;aside.append(start);
    const directReport=link('直接在线报修 →','#/customer/repair/new','fallback-link');directReport.addEventListener('click',()=>handoff([...(detail?.messages.items||[])].reverse().find(m=>m.role==='USER')?.turnId));aside.append(directReport);
    const panel=el('section',null,'workspace-panel chat-panel');
    const heading=el('div',null,'panel-heading');heading.append(el('h2','售后助手'),button('刷新消息',()=>ctx.run(()=>load(detail?.id,currentPage))));
    heading.lastChild.disabled=busy||!detail;panel.append(heading);
    if(ctx.mode==='PERSISTENCE_ONLY'){
      const notice=el('div',null,'ai-unavailable');
      notice.append(el('strong','AI 助手暂时不可用'),el('p','暂时无法提供智能回复。如需维修，可直接在线报修；已有咨询记录仍可查看。'),link('前往在线报修 →','#/customer/repair'));
      panel.append(notice);
    }else{
      const suggestions=el('div',null,'suggestions');suggestions.setAttribute('aria-label','咨询问题示例');
      for(const text of ['报修需要准备哪些信息？','怎样查看工单处理进度？','请准备报修']){
        const chip=button(text,()=>{messageText=text;render();document.getElementById('chat-message')?.focus();});chip.disabled=busy||!detail||!!pendingTurn;suggestions.append(chip);
      }panel.append(suggestions);
    }
    if(note)panel.append(el('p',note,'inline-notice'));
    const log=el('ol',null,'messages');log.id='messages';log.setAttribute('aria-label','已保存的对话消息');
    if(!detail)log.append(el('li','选定设备后开始咨询。请提供故障现象和已尝试的操作。','empty-state'));
    else if(!detail.messages.items.length)log.append(el('li','咨询已建立。描述设备问题即可开始。','empty-state'));
    for(const message of detail?.messages.items||[]){
      const item=el('li',null,'message '+(message.role==='USER'?'user-message':'assistant-message'));
      const meta=el('div',null,'message-meta');
      meta.append(el('span',message.role==='USER'?'你':'纸序助手','message-author'));
      const time=el('time',new Date(message.createdAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'}),'message-time');
      time.dateTime=message.createdAt;time.title=new Date(message.createdAt).toLocaleString();meta.append(time);item.append(meta);
      const reply=message.response;
      if(reply?.mode==='PERSISTENCE_ONLY')item.append(el('p','助手关闭 · 咨询记录已保存','response-status'));
      else if(reply&&['DRAFT_PREPARED','TICKET_CREATED','RESULT_UNKNOWN','WRITE_RESULT_UNKNOWN','PROCESSING'].includes(reply.status))
        item.append(el('p',statusNames[reply.status],'response-status'));
      item.append(el('p',reply?.text||message.content,'message-content'));
      if(reply){const label=answerStatus(reply);if(label)item.append(el('p',label,'answer-status'));item.append(answerDetails(reply,message.id));const panel=answerFeedback.panel(detail.id,message);if(panel)item.append(panel);}
      if(reply?.draft){
        const draft=reply.draft;
        item.append(link(`核对报修草稿 · 版本 ${draft.version}`,`#/customer/repair/${draft.id}`,'secondary-button'));
      }
      if(reply?.operation?.ticket)item.append(link('查看已提交工单',`#/customer/ticket/${reply.operation.ticket.id}`,'secondary-button'));
      if(mayReport(reply) && !reply.draft){
        const report=link('填写报修信息 →','#/customer/repair/new','conversation-repair-link');
        report.addEventListener('click',()=>handoff(message.turnId));
        item.append(report);
      }
      log.append(item);
    }
    panel.append(log);
    requestAnimationFrame(()=>{if(log.isConnected)log.scrollTop=log.scrollHeight;});
    let logWidth=log.clientWidth;
    logResize=new ResizeObserver(()=>{
      if(log.clientWidth!==logWidth){logWidth=log.clientWidth;log.scrollTop=log.scrollHeight;}
    });logResize.observe(log);
    if(detail&&detail.messages.totalPages>1){
      const pager=el('div',null,'pager');pager.append(button('上一页',()=>ctx.run(()=>load(detail.id,currentPage-1))),el('span',`${currentPage} / ${detail.messages.totalPages}`),button('下一页',()=>ctx.run(()=>load(detail.id,currentPage+1))));
      pager.firstChild.disabled=busy||currentPage<=1;pager.lastChild.disabled=busy||currentPage>=detail.messages.totalPages;panel.append(pager);
    }
    if(pendingTurn){
      const recover=el('div',null,'recovery');recover.append(el('p','上轮响应待核对。先读取已保存消息；如尚无最终结果，可用原轮次标识重试，不会开启第二轮。'));
      recover.append(button('查询原轮次',()=>ctx.run(async()=>{
        await load(pendingTurn.conversationId,1,true);
        if(pendingTurn)note='尚未取得最终结果。等待后再查询，或主动使用原轮次重试。';render();
      })),button('使用原轮次重试',()=>ctx.run(()=>send(true))));panel.append(recover);
    }
    const form=el('form');form.id='chat-form';
    const input=el('textarea');input.id='chat-message';input.rows=3;input.required=true;input.maxLength=8000;input.value=messageText;
    input.disabled=busy||!detail||!!pendingTurn;input.placeholder='描述故障、现场信息，或继续回答助手的补问';
    input.addEventListener('input',()=>{messageText=input.value;});
    form.append(field('你的问题',input));
    const submit=el('button',busy?'正在处理，请稍候…':ctx.mode==='PERSISTENCE_ONLY'?'保存咨询记录':'发送消息','primary-button');submit.type='submit';submit.id='send-message';submit.disabled=busy||!detail||!!pendingTurn;
    form.append(submit);form.addEventListener('submit',event=>{event.preventDefault();ctx.run(()=>send(false));});panel.append(form);root.append(aside,panel);
    root.querySelectorAll('button').forEach(b=>b.disabled ||= busy);
  }
  async function load(id,page=1,findPending=false){
    if(!id||disposed)return;
    const value=await api.get(`/api/conversations/${encodeURIComponent(id)}?page=${page}&size=${size}`);
    if(disposed)return;answerFeedback.refresh();detail=value;currentPage=page;selectedOrder=value.orderId;
    if(pendingTurn){
      // Read all pages only while reconciling a pending turn; normal browsing is paged.
      let found=value.messages.items.find(m=>m.role==='ASSISTANT'&&m.turnId===pendingTurn.turnId);
      if(findPending)for(let p=2;!found&&p<=value.messages.totalPages;p++){
        const batch=await api.get(`/api/conversations/${id}?page=${p}&size=${size}`);
        if(disposed)return;found=batch.messages.items.find(m=>m.role==='ASSISTANT'&&m.turnId===pendingTurn.turnId);
      }
      if(found&&!['PROCESSING','RESULT_UNKNOWN','WRITE_RESULT_UNKNOWN'].includes(found.response?.status)){
        pendingTurn=null;ctx.locator.save({pendingTurn:null});messageText='';note='已恢复原轮次结果。';
      }
    }
    render();
  }
  async function send(retry){
    if(disposed)return;const content=trim(messageText);
    if(!retry && (!content || [...content].length>4000))throw new Error('消息为必填，去除首尾空白后最多 4000 字。');
    if(!retry){
      pendingTurn={conversationId:detail.id,turnId:crypto.randomUUID(),content};
      // A request recovery envelope, never a displayed message or identity source.
      ctx.locator.save({pendingTurn});
    }
    if(!pendingTurn)return;
    busy=true;note='正在回复…';render();
    try{
      const turn=await api.turn(pendingTurn.conversationId,{turnId:pendingTurn.turnId,content:pendingTurn.content});
      if(disposed)return;
      if(!['PROCESSING','RESULT_UNKNOWN','WRITE_RESULT_UNKNOWN'].includes(turn.assistantMessage.response?.status)){
        pendingTurn=null;ctx.locator.save({pendingTurn:null});messageText='';note='';
      }else note='原轮次仍在处理，请稍后查询。';
      const total=detail.messages.total+(turn.replayed?0:2);
      await load(detail.id,Math.max(1,Math.ceil(total/size)));
    } finally {busy=false;render();}
  }
  return {async open(){
    const current=++epoch;busy=true;render();
    try{
      const all=[];let page=1,value;
      do{value=await api.get('/api/orders?page='+page+'&size=100');all.push(...value.items);page++;}while(page<=value.totalPages);
      if(current!==epoch)return;orders=all;
      const hint=ctx.locator.read();
      pendingTurn=hint.pendingTurn&&typeof hint.pendingTurn.content==='string'&&/^[a-f0-9-]{36}$/.test(hint.pendingTurn.turnId)?hint.pendingTurn:null;
      if(pendingTurn)messageText=pendingTurn.content;
      if(hint.conversationId)try{await load(hint.conversationId,1,true);}catch(e){if(e.status!==404&&e.status!==400)throw e;ctx.locator.save({conversationId:null,pendingTurn:null});pendingTurn=null;note='原咨询定位不可用，请选择本人设备开始咨询。';}
      const picked=orders.find(o=>o.id===hint.selectedOrderId);
      if(picked&&!pendingTurn){
        if(detail&&detail.orderId!==picked.id){detail=null;ctx.locator.save({conversationId:null});}
        selectedOrder=picked.id;ctx.locator.save({selectedOrderId:null});
      }else selectedOrder=detail?.orderId||orders[0]?.id||null;
    }finally{busy=false;render();}
  }, dispose(){disposed=true;epoch++;answerFeedback.dispose();logResize?.disconnect();root.remove();}};
}
