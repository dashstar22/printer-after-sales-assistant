import {serviceApi as api} from './api.js';
import {el,link,button,pairs,stateNames} from './ui.js';

function badge(text,className=''){return el('span',text,'badge '+className);}
function sectionTitle(text,action){const h=el('div',null,'panel-heading');h.append(el('h2',text));if(action)h.append(action);return h;}
function deviceCard(order,ctx){
  const card=el('article',null,'device-card');
  const heading=el('div',null,'device-heading');const icon=el('span','▣','device-icon');icon.setAttribute('aria-hidden','true');
  heading.append(icon,el('h3',order.modelCode));
  card.append(heading,badge(order.orderDemo?'演示订单':'实际业务设备'),badge(order.modelDemo?'演示型号':'登记型号'),pairs([['品牌',order.brand],['序列号',order.serialNo],['订单编号',order.orderNo],['购买日期',order.purchaseDate]]));
  const actions=el('div',null,'device-actions');
  for(const [text,href]of [['咨询此设备','#/customer/assistant'],['发起报修','#/customer/repair/new']]){
    const a=link(text,href,'secondary-button');a.addEventListener('click',()=>ctx.locator.save({selectedOrderId:order.id}));actions.append(a);
  }
  card.append(actions);return card;
}
export function homeScreen(host,ctx){
  const root=el('div',null,'dashboard');host.replaceChildren(root);let alive=true;
  return {async open(){
    const [orders,tickets,...counts]=await Promise.all([
      api.get('/api/orders?page=1&size=2'),api.get('/api/tickets?page=1&size=4'),
      ...['PENDING','IN_PROGRESS','CLOSED'].map(status=>api.get('/api/tickets?page=1&size=1&status='+status))
    ]);if(!alive)return;
    const welcome=el('section',null,'welcome-banner');
    const copy=el('div');copy.append(el('p','让每一次服务，都有回应。','eyebrow'),el('h2','你好，欢迎回来'),el('p','设备咨询、在线报修与处理进度，都在这里。'));
    welcome.append(copy,badge('打印机售后服务'));root.append(welcome);
    const actions=el('div',null,'service-actions');
    const entries=[['✦','AI 售后助手','描述设备问题，查看有来源的回复与报修补问。','#/customer/assistant',ctx.mode==='PERSISTENCE_ONLY'?'当前未启用':'开始咨询'],['＋','在线报修','填写故障与联系信息，核对后交给客服处理。','#/customer/repair/new','提交报修'],['▤','查看服务进度','跟进接单、维修记录和最终处理结果。','#/customer/tickets','查看工单']];
    for(const [symbol,title,text,href,cta]of entries){
      const a=link('',href,'service-action');a.append(el('span',symbol,'action-icon'),el('h3',title),el('p',text),el('span',cta+'  →','action-link'));actions.append(a);
    }root.append(actions);
    const lower=el('div',null,'overview-grid');
    const work=el('section',null,'workspace-panel');work.append(sectionTitle('我的服务',link('全部工单 →','#/customer/tickets','inline-link')));
    const stats=el('div',null,'service-stats');
    ['待接单','处理中','已关闭'].forEach((label,i)=>{const s=el('div');s.append(el('strong',counts[i].total),el('span',label));stats.append(s);});work.append(stats);
    if(!tickets.items.length)work.append(el('p','还没有报修工单。设备遇到问题时，可直接发起在线报修。','empty-state'));
    for(const ticket of tickets.items){
      const a=link('',`#/customer/ticket/${ticket.id}`,'recent-ticket');const info=el('div');info.append(el('strong',ticket.ticketNo),el('p',ticket.modelCode+' · '+ticket.orderNo));a.append(info,badge(stateNames[ticket.status],'state-'+ticket.status));work.append(a);
    }
    const devices=el('section',null,'workspace-panel');devices.append(sectionTitle('我的设备',link(`全部设备（${orders.total}） →`,'#/customer/devices','inline-link')));
    for(const order of orders.items)devices.append(deviceCard(order,ctx));
    if(!orders.items.length)devices.append(el('p','尚未绑定设备。请联系服务方核实本人身份及购买记录，由管理员登记后刷新；注册不会自动分配设备。','empty-state'));
    lower.append(work,devices);root.append(lower);
    const help=el('section',null,'service-guide');help.append(el('h2','一次报修，如何完成？'));
    const steps=el('ol');for(const [title,text]of [['描述问题','选择设备，填写故障与现场信息。'],['核对并提交','确认联系方式和报修内容，主动提交。'],['跟进处理','在工单中查看客服接单和处理记录。']]){const li=el('li');li.append(el('h3',title),el('p',text));steps.append(li);}help.append(steps);root.append(help);
  },dispose(){alive=false;root.remove();}};
}
export function devicesScreen(host,ctx){
  const root=el('section',null,'workspace-panel');host.replaceChildren(root);let page=1,alive=true;
  async function load(){
    const data=await api.get(`/api/orders?page=${page}&size=6`);if(!alive)return;
    root.replaceChildren(sectionTitle(`已关联设备 · ${data.total}`,button('刷新设备',()=>ctx.run(load))));
    const grid=el('div',null,'devices-grid');for(const order of data.items)grid.append(deviceCard(order,ctx));root.append(grid);
    if(!data.items.length)root.append(el('p','尚未绑定设备。请联系服务方核实本人身份及购买记录，由管理员登记后刷新。不要创建替代账号认领旧设备。','empty-state'));
    if(data.totalPages>1){const pager=el('div',null,'pager');const previous=button('上一页',()=>ctx.run(async()=>{page--;await load();}));const next=button('下一页',()=>ctx.run(async()=>{page++;await load();}));previous.disabled=page<=1;next.disabled=page>=data.totalPages;pager.append(previous,el('span',`${page} / ${data.totalPages}`),next);root.append(pager);}
  }
  return {open:load,dispose(){alive=false;root.remove();}};
}
