import {serviceApi} from './api.js';
import {el,field,button,feedback} from './ui.js';

export function invitationsScreen(host,ctx) {
  let active=true,busy=false,cursor=0,nextCursor=null,operation=null;
  const panel=el('section',null,'workspace-panel invitations-panel');panel.append(el('h2','客服邮箱邀请'),el('p','邀请仅开通客服；收件人通过邮件自行设置密码。已有业务账号请恢复原账号，旧客服应绑定原账号邮箱。','page-description'));
  const form=el('form'),email=el('input');email.id='invitation-email';email.type='email';email.maxLength=254;email.autocomplete='email';email.required=true;
  const send=el('button','发出客服邀请','primary-button');send.type='submit';send.id='invitation-submit';form.append(field('客服邮箱',email),send);
  const notice=el('p',null,'feedback');notice.hidden=true;notice.setAttribute('role','alert');
  const list=el('div',null,'invitation-list'),refresh=button('刷新邀请状态',()=>open()),next=button('下一页',()=>{cursor=nextCursor;open();}),resolved=button('已核对结果，开始另一操作',()=>{operation=null;resolved.hidden=true;show('已结束上一操作的重试；发起新邀请前请确认列表中没有相同待处理邀请。');});next.hidden=true;resolved.hidden=true;
  panel.append(form,notice,el('p','受理不代表邮件已送达。重发会替换旧链接；到期邀请可重发，已撤销或已接受邀请不可继续使用。','section-note'),refresh,resolved,list,next);host.replaceChildren(panel);
  function show(message){if(active){notice.textContent=message;notice.hidden=!message;}}
  function pending(value){busy=value;panel.querySelectorAll('input,button').forEach(node=>node.disabled=value);}
  function failed(error){if(error.status===401||error.status===403){list.replaceChildren();ctx.expired?.(error);ctx.onAuthError?.(error);return;}show(feedback(error));}
  async function open(){
    if(!active||busy)return;pending(true);
    try {
      const data=await serviceApi.get(`/api/admin/email-invitations?cursor=${cursor}&pageSize=20`);if(!active)return;
      list.replaceChildren();nextCursor=data.nextCursor;next.hidden=!nextCursor;resolved.hidden=!operation;
      if(!data.items?.length)list.append(el('p','暂无客服邀请。','empty-state'));
      for(const item of data.items||[]){
        const expired=item.status==='PENDING'&&Date.parse(item.expiresAt)<Date.now();
        const names={PENDING:'待接受',ACCEPTED:'已接受',REVOKED:'已撤销'};
        const row=el('article',null,'invitation-row');row.dataset.invitationId=item.id;
        row.append(el('h3',item.maskedEmail),el('p',`${expired?'已过期':names[item.status]||item.status} · 邀请编号 ${item.id}`),el('p',`到期：${item.expiresAt}`,'section-note'));
        if(item.status==='PENDING'){
          const resend=button('重新发送邀请',()=>mutate('resend',item.id)),revoke=button('撤销邀请',()=>mutate('revoke',item.id));
          resend.dataset.action='resend';revoke.dataset.action='revoke';row.append(resend,revoke);
        }list.append(row);
      }
    }catch(error){if(active)failed(error);}finally{if(active)pending(false);}
  }
  async function mutate(kind,id=null){
    if(!active||busy)return;
    const key=`${kind}:${id??email.value.trim().toLowerCase()}`;
    if(operation&&operation.key!==key){show('上一操作结果待核对，请先刷新邀请状态，或用原操作重试。');return;}
    if(!operation)operation={key,requestId:crypto.randomUUID(),email:email.value.trim()};
    pending(true);show('正在处理邀请…');
    try{
      const path=id?`/api/admin/email-invitations/${id}/${kind}`:'/api/admin/email-invitations';
      await serviceApi.write(path,id?{requestId:operation.requestId}:{requestId:operation.requestId,email:operation.email});
      if(!active)return;operation=null;resolved.hidden=true;show(kind==='revoke'?'邀请已撤销。':'邀请申请已记录，请检查邀请状态。受理不代表邮件已送达。');pending(false);await open();
    }catch(error){if(active){if(error.status!==0)operation=null;failed(error);if(error.status===0)show('处理结果待核对，请刷新邀请状态；重复原操作会沿用原标识，不要更换邮箱重新发起。');}}
    finally{if(active)pending(false);}
  }
  form.addEventListener('submit',event=>{event.preventDefault();if(form.reportValidity())mutate('invite');});
  return {open,dispose(){active=false;operation=null;email.value='';}};
}
