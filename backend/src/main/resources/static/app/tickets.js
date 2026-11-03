import {serviceApi as api} from './api.js';
import {el,button,link,field,pairs,stateNames,orderLabel,orderInfo,trim} from './ui.js';
import {createTicketHandoff,ticketIdentityFailure} from './ticket-handoff.js';
const fields=['Symptom','Extra','Contact','ContactMethod'];
const labels=['故障描述','现场补充信息','联系人','联系方式'];

export function ticketsScreen(host,ctx,id){
  const root=el('section',null,'workspace-panel ticket-panel');host.replaceChildren(root);
  const own=ctx.user.role==='ADMIN'?'admin':ctx.user.role==='STAFF'?'staff':'customer';
  let page=1,filter='',keyword='',data=null,ticket=null,note='',message='',uncertain=false,busy=false,counts=null,disposed=false;
  const handoff=id?createTicketHandoff(ctx,id,detail,value=>{message=value;}):null;
  async function list(){
    if(disposed)return;
    const params=new URLSearchParams({page:String(page),size:'10'});if(filter)params.set('status',filter);if(trim(keyword))params.set('keyword',trim(keyword));
    const [result,...totals]=await Promise.all([api.get('/api/tickets?'+params),...['PENDING','IN_PROGRESS','CLOSED'].map(status=>api.get('/api/tickets?page=1&size=1&status='+status))]);
    if(disposed)return;data=result;counts=totals.map(t=>t.total);render();
  }
  async function detail(reload=true){
    if(disposed)return;
    try{if(reload){const latest=await api.get('/api/tickets/'+id);if(disposed)return;await handoff.load();if(disposed)return;ticket=latest;uncertain=false;}render();}
    catch(error){if(!disposed&&(ticketIdentityFailure(error)||[403,404].includes(error.status))){ticket=null;root.replaceChildren();}throw error;}
  }
  function render(){
    if(disposed)return;
    root.replaceChildren();const header=el('div',null,'panel-heading');header.append(el('h2',id?'工单详情':own!=='customer'?'企业工单工作台':'我的工单'));
    header.append(button('刷新最新状态',()=>ctx.run(async()=>{id?await detail():await list();message='已刷新最新状态。';render();})));root.append(header);
    if(message)root.append(el('p',message,'inline-notice'));
    if(!id){
      if(counts){
        const summary=el('div',null,'ticket-summary');summary.setAttribute('aria-label','按状态快速查看工单');
        const values=[['','全部工单',counts.reduce((a,b)=>a+b,0)],...Object.entries(stateNames).map(([key,label],i)=>[key,label,counts[i]])];
        for(const [key,label,total]of values){const tab=button('',()=>{filter=key;page=1;ctx.run(list);});tab.append(el('span',label),el('strong',total));tab.setAttribute('aria-pressed',String(filter===key));summary.append(tab);}root.append(summary);
      }
      const form=el('form',null,'ticket-filters');form.id='ticket-filters';
      const status=el('select');status.id='ticket-status';status.append(new Option('全部状态',''));for(const [key,value]of Object.entries(stateNames))status.append(new Option(value,key));status.value=filter;
      const input=el('input');input.id='ticket-keyword';input.maxLength=100;input.value=keyword;input.placeholder='工单号 / 设备 / 故障';
      const submit=el('button','筛选工单','primary-button');submit.type='submit';
      form.append(field('状态',status),field('关键词',input),submit);
      form.addEventListener('submit',event=>{event.preventDefault();filter=status.value;keyword=input.value;page=1;ctx.run(list);});root.append(form);
      if(data){
        root.append(el('p',`共 ${data.total} 张工单`,'subtle'));
        if(!data.items.length)root.append(el('p','没有符合条件的工单。可调整筛选条件后再查询。','empty-state'));
        const ul=el('ul',null,'ticket-list');ul.id='ticket-list';
        for(const item of data.items){
          const li=el('li');const a=link('',`#/${own}/ticket/${item.id}`,'ticket-row');
          a.append(el('strong',item.ticketNo),el('span',item.modelCode+' · '+item.orderNo),el('span',stateNames[item.status]||item.status,'ticket-state'));li.append(a);ul.append(li);
        }root.append(ul);
        const pager=el('div',null,'pager');const prev=button('上一页',()=>ctx.run(async()=>{page--;await list();}));const next=button('下一页',()=>ctx.run(async()=>{page++;await list();}));
        prev.disabled=page<=1;next.disabled=page>=data.totalPages;pager.append(prev,el('span',`${page} / ${Math.max(1,data.totalPages)}`),next);root.append(pager);
      }
      if(own==='customer')root.append(link('发起在线报修',`#/customer/repair/new`,'secondary-button'));
    }else if(ticket){
      root.append(el('p',stateNames[ticket.status]||ticket.status,'detail-state'),pairs([['工单编号',ticket.ticketNo],['负责人',ticket.acceptedBy?'账号 '+ticket.acceptedBy:'尚未接单'],['设备订单',orderLabel(ticket.order)],['设备信息',orderInfo(ticket.order)],['当前版本',ticket.version],...fields.map((k,i)=>[labels[i],ticket['snapshot'+k]])]));
      root.append(el('h3','处理时间线'));const events=el('ol',null,'timeline');events.id='ticket-events';
      for(const event of ticket.events){const li=el('li');li.append(el('strong',({CREATED:'报修已提交',ACCEPTED:'客服已接单',NOTE:'处理记录',CLOSED:'工单已关闭',CUSTOMER_SUPPLEMENT:'客户补充',PHOTOS_ADDED:'故障照片已补充',TRANSFERRED:'负责人已转交'})[event.eventType]||event.eventType),el('p',event.note),el('span',`${new Date(event.createdAt).toLocaleString()} · 操作者 ${event.actorId}`,'subtle'));events.append(li);}root.append(events);
      handoff.render(root,ticket);
      if(own!=='customer'){
        const actions=el('section',null,'staff-actions');actions.append(el('h3','客服处理'));
        if(ticket.status==='PENDING'){const accept=button('接单并开始处理',()=>ctx.run(()=>handle('accept')),'primary-button');accept.id='accept-ticket';accept.disabled=uncertain||busy;actions.append(accept);}
        else if(ticket.status==='IN_PROGRESS'&&(own==='admin'||ticket.acceptedBy===ctx.user.id)){
          const text=el('textarea');text.id='handling-note';text.rows=4;text.maxLength=4000;text.value=note;text.disabled=busy||uncertain;text.addEventListener('input',()=>{note=text.value;});
          actions.append(field('处理记录 / 关闭结果（必填，最多 2000 字）',text));
          const buttons=el('div',null,'action-row');const add=button('保存处理记录',()=>ctx.run(()=>handle('notes')));add.id='add-ticket-note';
          const close=button('记录结果并关闭工单',()=>ctx.run(()=>handle('close')),'primary-button');close.id='close-ticket';
          add.disabled=close.disabled=busy||uncertain;buttons.append(add,close);actions.append(buttons);
        }else actions.append(el('p',ticket.status==='CLOSED'?'工单已关闭，不能再修改。':'当前由其他客服负责，仅负责人或管理员可保存处理记录。','inline-notice'));
        if(uncertain)actions.append(el('p','操作结果待核对或当前版本已过期。请刷新最新状态，再决定下一步。','inline-notice'));root.append(actions);
      }
      root.append(link('返回工单列表',`#/${own}/tickets`,'fallback-link'));
    }else root.append(el('p','正在读取工单…','empty-state'));
  }
  async function handle(action){
    if(disposed||busy||uncertain||!ticket)return;
    if(action!=='accept'&&(!trim(note)||[...trim(note)].length>2000))throw new Error('请填写处理记录或关闭结果，去除首尾空白后最多 2000 字。');
    busy=true;
    try{
      const body={expectedVersion:ticket.version};if(action!=='accept')body.note=note;
      await api.write(`/api/tickets/${ticket.id}/${action}`,body);note='';await detail();message=action==='close'?'结果已记录，工单已关闭。':action==='accept'?'已接单，工单进入处理中。':'处理记录已保存。';
    }catch(error){
      if(error.status===0||error.status===409){uncertain=true;message=error.status===409?'工单状态已变化，本次操作未成功，请刷新最新状态。':'未收到操作响应，不能确认是否已保存，请先刷新最新状态。';}
      throw error;
    }finally{busy=false;render();}
  }
  return {async open(){id?await detail():await list();},dispose(){disposed=true;handoff?.dispose();ticket=null;data=null;root.remove();}};
}
