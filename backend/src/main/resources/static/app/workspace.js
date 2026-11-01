import {serviceApi} from './api.js';
import {locatorFor} from './locator.js';
import {el,button,feedback} from './ui.js';
import {conversationScreen} from './conversation.js';
import {repairScreen} from './repair.js';
import {ticketsScreen} from './tickets.js';
import {homeScreen,devicesScreen} from './home.js';

export function createWorkspace(host, expired) {
  let user=null, route='', screen=null, generation=0, mode='PERSISTENCE_ONLY';
  const alert=el('p',null,'workspace-alert');alert.id='workspace-feedback';alert.setAttribute('role','status');alert.tabIndex=-1;
  const content=el('div');host.append(alert,content);
  const retry=button('重试读取页面',()=>{if(user){route='';open(user,location.hash.replace(/^#\//,'').split('/').slice(1).join('/'));}});
  retry.id='workspace-retry';retry.hidden=true;host.insertBefore(retry,content);
  function suspend(){host.hidden=true;}
  function reset(){generation++;screen?.dispose();screen=null;route='';user=null;content.replaceChildren();suspend();}
  async function open(identity,path){
    if(user?.id!==identity.id || user?.role!==identity.role){reset();user=identity;}
    host.hidden=false;
    if(route===path&&screen)return;
    route=path;screen?.dispose();screen=null;const current=++generation;
    alert.textContent='正在加载…';content.replaceChildren();
    retry.hidden=true;
    const own=user.role==='STAFF'?'staff':'customer';
    const bar=el('div',null,'workspace-heading');bar.append(el('div',null,'workspace-title'));
    const labels={home:['服务概览','从咨询到处理，随时掌握设备的服务进度。'],assistant:['AI 售后助手','选定设备，描述问题；有依据的回答可以展开查看来源。'],devices:['我的设备','查看本人购买的设备与订单，按设备发起服务。'],repair:['在线报修','填写现场情况，核对并确认后提交给客服。'],tickets:own==='staff'?['工单工作台','优先处理待接单工单，持续记录维修进展。']:['我的工单','查看报修记录，跟进每一次处理进展。'],ticket:['工单详情','查看报修内容和完整的服务记录。']};
    const [heading,description]=labels[path.split('/')[0]]||labels.home;
    const title=bar.firstChild;title.append(el('p',own==='staff'?'服务中心 / 客服工作台':'服务中心 / '+heading,'eyebrow'),el('h1',heading),el('p',description,'page-description'));
    host.prepend(bar);
    // Remove prior heading after route changes.
    for(const heading of [...host.querySelectorAll(':scope > .workspace-heading')].slice(1))heading.remove();
    const ctx={user,locator:locatorFor(user),mode,
      async run(action){
        if(current!==generation)return;
        if(ctx.busy)return;
        ctx.busy=true;alert.textContent='正在处理，请稍候…';content.setAttribute('aria-busy','true');
        const controls=[...content.querySelectorAll('button,input,select,textarea')];
        const disabled=controls.map(c=>c.disabled);controls.forEach(c=>c.disabled=true);
        try{await action();if(current===generation)alert.textContent='';}
        catch(error){
          if(current!==generation)return;
          if(error.status===401){reset();expired();return;}
          alert.textContent=feedback(error);alert.focus({preventScroll:true});
        }finally{
          ctx.busy=false;content.setAttribute('aria-busy','false');
          controls.forEach((c,i)=>{if(c.isConnected)c.disabled=disabled[i];});
        }
      },busy:false};
    try{
      const runtime=await serviceApi.get('/api/service-status');if(current!==generation)return;
      mode=runtime.assistantMode;ctx.mode=mode;
      if(mode==='SIMULATED_API')bar.append(el('p','当前为测试响应，不能作为设备处理依据。','runtime-note'));
      if(path==='home'&&own==='customer')screen=homeScreen(content,ctx);
      else if(path==='devices'&&own==='customer')screen=devicesScreen(content,ctx);
      else if(path==='assistant'&&own==='customer')screen=conversationScreen(content,ctx);
      else if((path==='repair'||path.startsWith('repair/'))&&own==='customer')screen=repairScreen(content,ctx,path.split('/')[1]);
      else if(path==='tickets'||/^ticket\/\d+$/.test(path))screen=ticketsScreen(content,ctx,path.split('/')[1]);
      else {content.append(el('p','该页面尚未接入。'));return;}
      await ctx.run(()=>screen.open());
      if(current===generation)retry.hidden=!alert.textContent;
      if(current===generation&&!alert.textContent)host.focus({preventScroll:true});
    }catch(error){if(current===generation){alert.textContent=feedback(error);retry.hidden=false;if(error.status===401){reset();expired();}}}
  }
  return {open,suspend,reset};
}
