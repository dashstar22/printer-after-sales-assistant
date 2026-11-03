import {authApi} from './api.js';
import {createState, entryFor, routeFor} from './state.js';
import {loginView} from './views/login.js';
import {registerView} from './views/register.js';
import {statusView} from './views/status.js';
import {captureEmailAction,requestEmailView,confirmEmailView,legacyLoginView,bindingView,changePasswordView} from './views/email.js';
import {startAtmosphere} from './atmosphere.js';
import {createWorkspace} from './workspace.js';
import {link,el,userLabel} from './ui.js';
import {clearAutoLoginPreference} from './login-preferences.js';

let emailAction=captureEmailAction();
const state=createState();
const entry=document.getElementById('entry'),identity=document.getElementById('identity'),logout=document.getElementById('logout'),navigation=document.getElementById('navigation');
let sequence=0,atmosphere,lastView,entryView=null,entryName=null,identityChecking=false,refreshQueued=false;
const workspace=createWorkspace(document.getElementById('workspace'),identityFailure);
const business=['customer','staff','admin'];
function replaceRoute(route){history.replaceState(null,'','#/'+route);}
function clearRecovery(){try{for(const key of Object.keys(sessionStorage))if(key.startsWith('printer.service.ticket-request.v1.')||key.startsWith('printer.service.answer-feedback.v1.'))sessionStorage.removeItem(key);}catch{}}
function networkMessage(error,fallback){return error.status===0?fallback:error.message||fallback;}
function sameIdentity(a,b){return a?.id===b?.id&&a?.role===b?.role&&a?.username===b?.username&&a?.displayName===b?.displayName&&a?.emailVerified===b?.emailVerified&&a?.passwordChangeRequired===b?.passwordChangeRequired&&a?.passwordAuthenticated===b?.passwordAuthenticated&&a?.legacyMode===b?.legacyMode;}
async function refresh({background=false}={}){
  if(state.get().pending||identityChecking)return;
  identityChecking=true;
  const previous=state.get(),hadUser=!!previous.user,quiet=background&&['anonymous','authenticated'].includes(previous.phase),current=++sequence;
  if(!quiet)state.update({phase:'checking',pending:'identity',user:null,error:'',notice:''});
  try{
    const user=await authApi.current();if(current!==sequence)return;
    if(quiet&&sameIdentity(previous.user,user))return;
    if(!sameIdentity(previous.user,user)){entryView?.dispose?.();entryView=null;entryName=null;}
    if(previous.user&&previous.user.id!==user.id){workspace.reset();clearRecovery();}
    if(!emailAction&&(!location.hash||['#/login','#/register','#/legacy-login'].includes(location.hash)||quiet&&previous.user?.id!==user.id))replaceRoute(entryFor(user));
    state.update({phase:'authenticated',user,pending:null});
  }catch(error){
    if(current!==sequence)return;
    if(error.status===401){
      clearRecovery();
      if(quiet&&!hadUser)return;
      workspace.reset();
      state.update({phase:'anonymous',user:null,pending:null,notice:hadUser?'登录已失效，请重新登录。':''});
    }else if(!quiet){workspace.reset();state.update({phase:'unavailable',user:null,pending:null,error:networkMessage(error,'无法取得身份核对结果，请检查连接后重试。')});}
  }finally{identityChecking=false;if(refreshQueued){refreshQueued=false;refresh();}}
}
async function login(username,password,remember,legacy=false){
  if(state.get().pending)return;
  const current=++sequence;state.update({phase:'anonymous',user:null,username,pending:'login',error:'',notice:''});
  try{
    const user=await authApi.login(username,password,legacy?false:remember);if(current!==sequence)return;
    clearRecovery();workspace.reset();
    replaceRoute(emailAction?.purpose==='BIND'&&!user.passwordChangeRequired?'email-confirm':entryFor(user));
    state.update({phase:'authenticated',user,pending:null,error:'',notice:''});
  }catch(error){
    if(current!==sequence)return;
    if(error.status===401)clearRecovery();
    state.update({phase:error.status===401?'anonymous':'unavailable',user:null,pending:null,error:networkMessage(error,legacy?'未能确认原账号登录结果，请重新核对身份。':'无法确认登录结果，请重新核对身份后再操作。')});
  }
}
async function signOut(keepAction=false,route='login'){
  if(state.get().pending)return;
  const current=++sequence;state.update({pending:'logout',error:'',notice:''});
  try{
    await authApi.logout();if(current!==sequence)return;
    if(!keepAction)emailAction=null;
    replaceRoute(keepAction&&route==='login'?'email-confirm':route);clearAutoLoginPreference();clearRecovery();workspace.reset();
    entryView?.dispose?.();entryName=null;
    state.update({phase:'anonymous',user:null,username:'',pending:null,error:'',notice:'已退出登录。'});
  }catch(error){if(current!==sequence)return;workspace.reset();if(error.status===401){clearRecovery();clearAutoLoginPreference();emailAction=null;replaceRoute('login');state.update({phase:'anonymous',user:null,pending:null,error:'',notice:'登录已失效，请重新登录。'});}else state.update({phase:'unavailable',user:null,pending:null,error:networkMessage(error,'未能确认退出结果，请重新核对身份。')});}
}
function identityFailure(error){
  ++sequence;workspace.reset();clearRecovery();
  if(error.status===401){refreshQueued=false;state.update({phase:'anonymous',user:null,pending:null,error:'',notice:'登录已失效，请重新登录。'});}
  else {
    state.update({phase:'checking',user:null,pending:null,error:'',notice:''});
    if(identityChecking)refreshQueued=true;else refresh();
  }
}
function emailCompleted(purpose){
  emailAction=null;clearAutoLoginPreference();clearRecovery();workspace.reset();replaceRoute('login');
  const notice=purpose==='BIND'?'原账号邮箱绑定完成，请使用绑定邮箱重新登录。':purpose==='RESET'?'密码已重设，请使用邮箱和新密码重新登录。':purpose==='INVITE'?'客服邀请已接受，请使用受邀邮箱登录。':'客户账号注册成功，请在此页面使用邮箱登录；原注册页面可以关闭。';
  state.update({phase:'anonymous',user:null,pending:null,error:'',notice});
}
function passwordChanged(){emailAction=null;workspace.reset();clearRecovery();clearAutoLoginPreference();replaceRoute('legacy-login');state.update({phase:'anonymous',user:null,pending:null,error:'',notice:'密码已修改，请用原账号和新密码重新验证，再申请绑定邮箱。'});}
const actions=Object.freeze({login,refresh,signOut,identityFailure,emailCompleted,passwordChanged,emailInput:email=>state.update({emailInput:email})});
function render(value){
  const own=entryFor(value.user),hash=location.hash;
  const route=value.phase==='authenticated'?routeFor(hash,value.user):value.phase;
  let name=route;
  if(['anonymous','authenticated'].includes(value.phase)&&hash==='#/email-confirm')name='email-confirm';
  else if(value.phase==='anonymous')name=hash==='#/register'?'register':hash==='#/forgot-password'?'forgot-password':hash==='#/legacy-login'?'legacy-login':'login';
  const roles={ADMIN:'管理员',STAFF:'客服',CUSTOMER:'客户'};
  identity.textContent=value.user?`${roles[value.user.role]} · ${userLabel(value.user)}`:'';identity.title=value.user?userLabel(value.user):'';logout.hidden=!value.user;logout.disabled=!!value.pending;logout.textContent=value.pending==='logout'?'正在退出':'退出登录';
  const inWorkspace=business.includes(name);
  navigation.hidden=!value.user||!business.includes(own)||!inWorkspace;navigation.replaceChildren();
  if(value.user&&business.includes(own)&&inWorkspace){
    const items=own==='admin'?[['accounts','账号状态','◈'],['invitations','客服邀请','＋'],['models','型号与别名','▣'],['orders','设备订单','▤'],['imports','订单导入','↑'],['knowledge','知识资料','▥'],['tickets','工单工作台','▤']]:own==='staff'?[['tickets','工单工作台','▤']]:[['','服务概览','◈'],['assistant','AI 售后助手','✦'],['repair','在线报修','＋'],['devices','我的设备','▣'],['tickets','我的工单','▤']];
    navigation.append(el('p',own==='admin'?'业务管理':own==='staff'?'客服工作空间':'客户服务','nav-label'));
    const current=hash.replace(/^#\//,'').split('/')[1]||(own==='admin'?'accounts':own==='staff'?'tickets':'');
    for(const [path,label,mark]of items){const a=link('',`#/${own}${path?'/'+path:''}`),icon=el('span',mark,'nav-icon');icon.setAttribute('aria-hidden','true');a.append(icon,el('span',label));if(current===path||path==='tickets'&&current==='ticket')a.setAttribute('aria-current','page');if(!path)a.id='home-link';if(path==='repair')a.id='repair-link';navigation.append(a);}
  }
  const entryNames=['login','register','forgot-password','legacy-login','email-confirm','bind-email','change-password'];
  if(entryNames.includes(name)){
    if(entryName!==name){entryView?.dispose?.();
      const factories={login:loginView,register:registerView,'forgot-password':(s,a)=>requestEmailView('RESET',s,a),'legacy-login':legacyLoginView,'email-confirm':(s,a)=>confirmEmailView(emailAction,s,a),'bind-email':bindingView,'change-password':changePasswordView};
      entryView=factories[name](value,actions);entry.replaceChildren(entryView.element);
    }entryView.update(value);
  }else{entryView?.dispose?.();entryView=null;entry.replaceChildren(inWorkspace?document.createDocumentFragment():statusView(value,actions,name==='denied'));}
  entryName=name;entry.dataset.view=name;entry.setAttribute('aria-busy',String(!!value.pending));
  const subroute=hash.replace(/^#\//,'').split('/').slice(1).join('/');document.getElementById('scene').classList.toggle('workspace-open',inWorkspace);document.querySelector('.entry-region').hidden=inWorkspace;
  if(inWorkspace)workspace.open(value.user,subroute||(own==='admin'?'accounts':own==='staff'?'tickets':'home'));else workspace.suspend();
  if(name!==lastView&&name!=='checking')atmosphere?.revealEntry();lastView=name;
}
state.subscribe(render);
document.querySelector('.skip-link').addEventListener('click',event=>{event.preventDefault();const target=document.getElementById('workspace');(target.hidden?entry:target).focus();});
logout.addEventListener('click',()=>signOut());
addEventListener('hashchange',()=>{
  const next=captureEmailAction();if(next){emailAction=next;entryName=null;entryView?.dispose?.();}
  else if(!['#/email-confirm','#/legacy-login','#/bind-email','#/change-password'].includes(location.hash))emailAction=null;
  render(state.get());
});
addEventListener('focus',()=>refresh({background:true}));addEventListener('pageshow',event=>{if(event.persisted)refresh({background:true});});document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh({background:true});});
render(state.get());refresh();startAtmosphere().then(value=>{atmosphere=value;});
