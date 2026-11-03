import {el,button,field,link,feedback} from '../ui.js';
import {authApi} from '../api.js';

const ACCEPTED='如可受理，将向邮箱发送操作链接。请检查收件箱和垃圾邮件，稍后可重新申请。受理不代表邮件已送达或账号已开通。';
const labels={REGISTER:'验证邮箱并创建客户账号',RESET:'设置新的登录密码',INVITE:'接受客服邀请',BIND:'确认绑定原账号邮箱'};
export function captureEmailAction() {
  if(!location.hash.startsWith('#email-action='))return null;
  const params=new URLSearchParams(location.hash.slice(1));
  const purpose=params.get('email-action'),token=params.get('token');
  history.replaceState(null,'','#/email-confirm');
  return Object.freeze({purpose,token:/^[A-Za-z0-9_-]{43}$/.test(token||'')&&Object.hasOwn(labels,purpose)?token:null});
}
function control(id,type='text') {
  const input=el('input');input.id=id;input.type=type;input.required=true;
  if(type==='email'){input.maxLength=254;input.autocomplete='email';input.inputMode='email';}
  if(type==='password'){input.minLength=8;input.maxLength=64;input.autocomplete='new-password';}
  return input;
}
function base(title,description) {
  const element=el('div',null,'email-view');element.append(el('h2',title),el('p',description,'card-copy'));
  const form=el('form'),fields=el('div',null,'fields'),notice=el('p',null,'feedback');notice.setAttribute('role','alert');notice.hidden=true;
  const submit=el('button','继续','primary-button');submit.type='submit';form.append(fields,notice,submit);element.append(form);
  let active=true,busy=false,blocked=false;
  function show(message){if(!active)return;notice.textContent=message;notice.hidden=!message;}
  function controls(){form.querySelectorAll('input,button').forEach(n=>n.disabled=busy||blocked);form.setAttribute('aria-busy',String(busy||blocked));}
  function pending(value){busy=value;controls();}
  return {element,form,fields,submit,show,pending,isBusy:()=>busy||blocked,isActive:()=>active,dispose(){active=false;form.querySelectorAll('input[type=password]').forEach(n=>n.value='');},update(value){blocked=!!value.pending;controls();}};
}
function passwordFields(view) {
  const password=control('email-password','password'),confirmation=control('email-password-confirmation','password');
  view.fields.append(field('新密码（8–64 位）',password),field('再次输入新密码',confirmation));
  function validate(){confirmation.setCustomValidity(confirmation.value&&confirmation.value!==password.value?'两次输入的密码不一致':'');}
  password.addEventListener('input',validate);confirmation.addEventListener('input',validate);
  return {password,confirmation,validate};
}
function retryLinks(view,purpose) {
  const links=el('p',null,'auth-links');
  if(purpose==='REGISTER')links.append(link('重新申请注册邮件','#/register'));
  else if(purpose==='RESET')links.append(link('重新申请找回邮件','#/forgot-password'));
  else if(purpose==='BIND')links.append(link('返回原账号绑定','#/bind-email'));
  else links.append(el('span','邀请失效或未收到？请联系管理员重发邀请。'));
  links.append(link('返回邮箱登录','#/login'));view.element.append(links);
}
export function requestEmailView(purpose,state,actions) {
  const isRegister=purpose==='REGISTER';
  const view=base(isRegister?'创建客户账号':'找回登录密码',isRegister?'使用邮箱注册客户账号，验证邮箱并设置密码后即可登录。设备需由服务方核实并登记。':'仅已验证邮箱可以自助找回。未绑定邮箱的旧账号请联系负责人核实恢复原账号。');
  const email=control(isRegister?'register-email':'reset-email','email');email.value=state.emailInput||'';
  view.fields.append(field('邮箱',email));view.submit.id=isRegister?'register-submit':'reset-request-submit';view.submit.textContent=isRegister?'申请注册邮件':'申请找回邮件';
  let until=0,timer;
  function cooldown(){const left=Math.ceil((until-Date.now())/1000);view.submit.disabled=left>0||view.isBusy();view.submit.textContent=left>0?`${left} 秒后可重新申请`:'重新申请邮件';if(left<=0)clearInterval(timer);}
  const dispose=view.dispose;view.dispose=()=>{clearInterval(timer);dispose();};
  view.form.addEventListener('submit',async event=>{
    event.preventDefault();if(view.isBusy()||Date.now()<until||!view.form.reportValidity())return;
    actions.emailInput(email.value.trim());view.pending(true);view.show('正在受理，请稍候。');
    try {const result=await (isRegister?authApi.register(email.value.trim()):authApi.resetRequest(email.value.trim()));if(result?.accepted!==true)throw new Error('未能确认受理结果，请稍后重新申请。');view.show(ACCEPTED+(isRegister?' 请在邮件打开的页面完成注册并登录，原注册页面可以关闭。':''));until=Date.now()+60000;}
    catch(error){view.show(error.status===429?'申请过于频繁，请 15 分钟后再试。':error.status===0?'受理结果待确认。请先检查邮件，60 秒后再重新申请。':feedback(error));if(error.status===0)until=Date.now()+60000;}
    finally {if(view.isActive()){view.pending(false);if(until>Date.now()){cooldown();timer=setInterval(cooldown,1000);}}}
  });
  view.element.append(el('p','请勿转发邮件链接。重新申请后，旧链接将失效。','section-note'),link('返回邮箱登录','#/login','auth-switch'));return view;
}
export function confirmEmailView(action,state,actions) {
  const purpose=action?.purpose,view=base(labels[purpose]||'邮件链接不可用','打开邮件不会变更账号。请核对操作后明确确认；链接仅可使用一次。');view.submit.id='email-confirm-submit';retryLinks(view,purpose);
  if(!action?.token){view.show('链接缺失或不可用，请重新打开完整邮件链接。页面刷新后需重新打开邮件，令牌不会被保存。');view.fields.hidden=true;view.submit.hidden=true;return view;}
  if(purpose==='BIND'&&(!state.user||state.user.emailVerified||!state.user.passwordAuthenticated||state.user.passwordChangeRequired)) {
    view.form.hidden=true;view.element.append(el('p','请先在此浏览器以需要绑定的原账号和当前密码登录。若需要改密，请改密后重新申请绑定邮件。','feedback'),state.user?button('退出并验证原账号',()=>actions.signOut(true,'legacy-login'),'primary-button'):link('验证原账号身份','#/legacy-login','primary-button'));return view;
  }
  if(state.user&&['REGISTER','INVITE'].includes(purpose)) {view.form.hidden=true;view.element.append(el('p','当前浏览器已有登录账号。请先退出，再在本页继续邮件操作。','feedback'),button('退出当前账号并继续',()=>actions.signOut(true),'primary-button'));return view;}
  let passwords;
  if(purpose==='BIND')view.fields.append(el('p',`将邮箱绑定到当前原账号：${state.user.username}。绑定后原订单和工单仍属于此账号。`));else passwords=passwordFields(view);
  view.submit.textContent=purpose==='BIND'?'确认绑定邮箱':purpose==='INVITE'?'接受邀请并设置密码':purpose==='REGISTER'?'验证并创建客户账号':'确认重设密码';
  view.form.addEventListener('submit',async event=>{
    event.preventDefault();passwords?.validate();if(view.isBusy()||!view.form.reportValidity())return;
    const body=purpose==='BIND'?{token:action.token}:{token:action.token,password:passwords.password.value,passwordConfirmation:passwords.confirmation.value};
    if(passwords){passwords.password.value='';passwords.confirmation.value='';}view.pending(true);view.show('正在核对邮件链接…');
    try {const result=await authApi.confirm(purpose,body);if(!view.isActive())return;if(result?.completed!==true)throw new Error('未能确认操作结果，请重新登录核对。');actions.emailCompleted(purpose);}
    catch(error){if(!view.isActive())return;if(error.status===401){actions.identityFailure(error);return;}let message=error.status===0?'未能确认提交结果。请先使用邮箱登录核对；如仍未完成，再重新申请邮件。':feedback(error);if(error.code==='EMAIL_CONFLICT')message+=' 请恢复已有原账号或联系负责人核实，不要创建替代账号认领设备。';view.show(message);}
    finally{if(view.isActive())view.pending(false);}
  });return view;
}
export function legacyLoginView(state,actions) {
  const view=base('旧账号邮箱绑定入口','仅限已有账号或经负责人核实后获得限时恢复资格的账号。原用户名与当前密码只用于验证原身份；不能访问业务。入口关闭或忘记原密码时，请联系负责人核实恢复原账号。');
  const username=control('legacy-username'),password=control('legacy-password','password');username.autocomplete='username';username.maxLength=100;password.autocomplete='current-password';password.minLength=1;
  view.fields.append(field('原用户名',username),field('原账号当前密码',password));view.submit.textContent='验证原账号';view.submit.id='legacy-login-submit';let current=state;
  view.update=value=>{current=value;view.pending(!!value.pending);view.show(value.error||value.notice);};
  view.form.addEventListener('submit',event=>{event.preventDefault();if(current.pending||!view.form.reportValidity())return;const credential=password.value;password.value='';actions.login(username.value.trim(),credential,false,true);});
  view.element.append(link('返回邮箱登录','#/login','auth-switch'));view.update(state);return view;
}
export function bindingView(state,actions) {
  const view=base('为原账号验证绑定邮箱','邮箱验证完成后，原账号 ID、角色、订单、咨询和工单归属保持。请使用仍由本人控制且尚未注册其他账号的邮箱。');view.element.insertBefore(el('p',`原账号：${state.user.username}`,'section-note'),view.form);
  if(!state.user.passwordAuthenticated){view.form.hidden=true;view.element.append(el('p','自动登录不能完成邮箱绑定。请退出后用原账号当前密码验证。','feedback'),button('退出并验证原账号',()=>actions.signOut(false,'legacy-login'),'primary-button'));return view;}
  const email=control('bind-email','email'),password=control('bind-current-password','password');email.value=state.emailInput||'';password.autocomplete='current-password';view.fields.append(field('待验证绑定的邮箱',email),field('原账号当前密码',password));view.submit.id='bind-request-submit';view.submit.textContent='申请绑定邮件';let until=0,timer;
  const dispose=view.dispose;view.dispose=()=>{clearInterval(timer);dispose();};
  view.form.addEventListener('submit',async event=>{
    event.preventDefault();if(view.isBusy()||Date.now()<until||!view.form.reportValidity())return;
    const credential=password.value;password.value='';actions.emailInput(email.value.trim());view.pending(true);
    try {const result=await authApi.bindRequest(email.value.trim(),credential);if(result?.accepted!==true)throw new Error('未能确认受理结果。');view.show(ACCEPTED+' 请在当前浏览器打开绑定邮件。');until=Date.now()+60000;}
    catch(error){if(!view.isActive())return;if(error.status===401||error.status===403){actions.identityFailure(error);return;}view.show(error.code==='EMAIL_CONFLICT'?'此邮箱已有账号。请恢复原账号或联系负责人核实，不可合并或转移原订单。':feedback(error));}
    finally{if(view.isActive()){view.pending(false);if(until>Date.now()){const tick=()=>{const left=Math.ceil((until-Date.now())/1000);view.submit.disabled=left>0;view.submit.textContent=left>0?`${left} 秒后可重新申请`:'重新申请绑定邮件';if(left<=0)clearInterval(timer);};tick();timer=setInterval(tick,1000);}}}
  });view.element.append(el('p','邮箱已占用或原密码丢失，请联系负责人核实恢复原账号；不能注册替代账号认领原设备。','section-note'));return view;
}
export function changePasswordView(state,actions) {
  const view=base('先修改原账号密码','此账号需要先更新密码。成功后须重新验证身份，再申请绑定本人邮箱。');const current=control('change-current-password','password');current.autocomplete='current-password';view.fields.append(field('当前密码',current));const passwords=passwordFields(view);view.submit.id='change-password-submit';view.submit.textContent='修改密码并重新登录';let requestId=crypto.randomUUID();
  view.form.addEventListener('submit',async event=>{
    event.preventDefault();passwords.validate();if(view.isBusy()||!view.form.reportValidity())return;
    const old=current.value,next=passwords.password.value;current.value='';passwords.password.value='';passwords.confirmation.value='';view.pending(true);
    try{const result=await authApi.changePassword(old,next,requestId);if(!view.isActive())return;if(result?.changed!==true)throw new Error('未能确认修改结果，请重新登录核对。');actions.passwordChanged();}
    catch(error){if(!view.isActive())return;if(error.status===401){actions.identityFailure(error);return;}view.show(error.status===0?'修改结果待核对，请先退出并用新密码重新登录，不要直接重复修改。':feedback(error));if(error.status!==0)requestId=crypto.randomUUID();}
    finally{if(view.isActive())view.pending(false);}
  });return view;
}
