import {serviceApi} from './api.js';
import {locatorFor} from './locator.js';
import {el,button,feedback,link} from './ui.js';
import {conversationScreen} from './conversation.js';
import {repairScreen} from './repair.js';
import {ticketsScreen} from './tickets.js';
import {homeScreen,devicesScreen} from './home.js';
import {administrationScreen} from './admin.js';
import {invitationsScreen} from './invitations.js';
import {knowledgeScreen} from './knowledge.js';

export function createWorkspace(host, expired) {
  let user=null, route='', screen=null, generation=0, mode='PERSISTENCE_ONLY';
  const alert=el('p',null,'workspace-alert');alert.id='workspace-feedback';alert.setAttribute('role','status');alert.tabIndex=-1;
  const content=el('div');host.append(alert,content);
  const retry=button('重试读取页面',()=>{if(user){route='';open(user,location.hash.replace(/^#\//,'').split('/').slice(1).join('/'));}});
  retry.id='workspace-retry';retry.hidden=true;host.insertBefore(retry,content);
  function suspend(){host.hidden=true;}
  function reset(){generation++;screen?.dispose();screen=null;route='';user=null;content.replaceChildren();suspend();}
  function identityFailure(error){
    return error.status===401 || error.status===403 && ['EMAIL_BIND_REQUIRED','EMAIL_BINDING_REQUIRED','PASSWORD_CHANGE_REQUIRED','PASSWORD_AUTHENTICATION_REQUIRED','PASSWORD_LOGIN_REQUIRED'].includes(error.code);
  }
  function denied(){
    screen?.dispose();screen=null;content.replaceChildren(el('p','没有读取此页面的权限，请返回自己的服务入口。','empty-state'),link('返回我的入口',user?.role==='ADMIN'?'#/admin/accounts':user?.role==='STAFF'?'#/staff/tickets':'#/customer','secondary-button'));
    alert.textContent='没有执行此操作的权限。';retry.hidden=true;
  }
  async function open(identity,path){
    if(user?.id!==identity.id || user?.role!==identity.role){reset();user=identity;}
    host.hidden=false;
    if(route===path&&screen)return;
    route=path;screen?.dispose();screen=null;const current=++generation;
    alert.textContent='正在加载…';content.replaceChildren();
    retry.hidden=true;
    const own=user.role==='ADMIN'?'admin':user.role==='STAFF'?'staff':'customer';
    const bar=el('div',null,'workspace-heading');bar.append(el('div',null,'workspace-title'));
    const labels={home:['服务概览','从咨询到处理，随时掌握设备的服务进度。'],assistant:['AI 售后助手','选定设备，描述问题；有依据的回答可以展开查看来源。'],devices:['我的设备','查看本人购买的设备与订单，按设备发起服务。'],repair:['在线报修','填写现场情况，核对并确认后提交给客服。'],tickets:own==='staff'?['工单工作台','优先处理待接单工单，持续记录维修进展。']:['我的工单','查看报修记录，跟进每一次处理进展。'],ticket:['工单详情','查看报修内容和完整的服务记录。']};
    Object.assign(labels,{accounts:['账号维护','核对账号状态，管理客服邀请。'],models:['型号与别名','维护标准型号及已审核的别名。'],orders:['设备订单','核实客户身份后登记设备归属。'],imports:['批量导入','先检查文件，再确认同一批设备订单。'],invitations:['客服邀请','邀请收件人验证邮箱并自行设置密码。'],knowledge:['知识资料','登记来源、检查原文分块，再确认发布；处理进度始终可查。']});
    if(own==='admin')labels.tickets=['工单工作台','查看负责人、处理进展与客服转交记录。'];
    const [heading,description]=labels[path.split('/')[0]]||labels.home;
    const title=bar.firstChild;title.append(el('p',own==='staff'?'服务中心 / 客服工作台':'服务中心 / '+heading,'eyebrow'),el('h1',heading),el('p',description,'page-description'));
    host.prepend(bar);
    // Remove prior heading after route changes.
    for(const heading of [...host.querySelectorAll(':scope > .workspace-heading')].slice(1))heading.remove();
    const ctx={user,locator:locatorFor(user),mode,
      onAuthError(error){if(current!==generation)return;if(identityFailure(error)){reset();expired(error);}else if(error.status===403)denied();},
      async run(action){
        if(current!==generation)return;
        if(ctx.busy)return;
        ctx.busy=true;alert.textContent='正在处理，请稍候…';content.setAttribute('aria-busy','true');
        const controls=[...content.querySelectorAll('button,input,select,textarea')];
        const disabled=controls.map(c=>c.disabled);controls.forEach(c=>c.disabled=true);
        try{await action();if(current===generation)alert.textContent='';}
        catch(error){
          if(current!==generation)return;
          if(identityFailure(error)){reset();expired(error);return;}
          if(error.status===403){denied();return;}
          alert.textContent=feedback(error);alert.focus({preventScroll:true});
        }finally{
          ctx.busy=false;content.setAttribute('aria-busy','false');
          controls.forEach((c,i)=>{if(c.isConnected)c.disabled=disabled[i];});
        }
      },busy:false};
    try{
      const runtime=await serviceApi.get('/api/service-status');if(current!==generation)return;
      mode=runtime.assistantMode;ctx.mode=mode;
      if(runtime.deploymentMode==='PORTFOLIO_REAL')bar.append(el('p','真实 AI 演示 · 业务与样例资料为模拟数据，请核对回答原文。','runtime-note'));
      else if(runtime.deploymentMode==='PORTFOLIO_OFFLINE')bar.append(el('p','离线演示 · 模型为本地测试协议，不能作为设备处理依据。','runtime-note'));
      else if(mode==='SIMULATED_API')bar.append(el('p','当前为测试响应，不能作为设备处理依据。','runtime-note'));
      if(path==='home'&&own==='customer')screen=homeScreen(content,ctx);
      else if(path==='devices'&&own==='customer')screen=devicesScreen(content,ctx);
      else if(path==='assistant'&&own==='customer')screen=conversationScreen(content,ctx);
      else if((path==='repair'||path.startsWith('repair/'))&&own==='customer')screen=repairScreen(content,ctx,path.split('/')[1]);
      else if(path==='tickets'||/^ticket\/\d+$/.test(path))screen=ticketsScreen(content,ctx,path.split('/')[1]);
      else if(own==='admin'&&['accounts','models','orders','imports'].includes(path))screen=administrationScreen(content,ctx,path);
      else if(own==='admin'&&path==='invitations')screen=invitationsScreen(content,ctx);
      else if(own==='admin'&&/^knowledge(\/\d+)?$/.test(path))screen=knowledgeScreen(content,ctx,path.split('/')[1]);
      else {content.append(el('p','该页面尚未接入。'));return;}
      await ctx.run(()=>screen.open());
      if(current===generation)retry.hidden=!alert.textContent;
      if(current===generation&&!alert.textContent)host.focus({preventScroll:true});
    }catch(error){if(current===generation){alert.textContent=feedback(error);retry.hidden=false;if(identityFailure(error)){reset();expired(error);}else if(error.status===403)denied();}}
  }
  return {open,suspend,reset};
}
