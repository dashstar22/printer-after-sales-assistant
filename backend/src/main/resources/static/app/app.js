import {authApi} from './api.js';
import {createState, entryFor, routeFor} from './state.js';
import {loginView} from './views/login.js';
import {registerView} from './views/register.js';
import {statusView} from './views/status.js';
import {startAtmosphere} from './atmosphere.js';
import {createWorkspace} from './workspace.js';
import {link,el} from './ui.js';
import {clearAutoLoginPreference} from './login-preferences.js';

const state = createState();
const entry = document.getElementById('entry');
const identity = document.getElementById('identity');
const logout = document.getElementById('logout');
const navigation = document.getElementById('navigation');
let sequence = 0;
let atmosphere;
let lastView;
let entryView = null;
let entryName = null;
let identityChecking = false;
const workspace = createWorkspace(document.getElementById('workspace'), () => refresh());

function replaceRoute(route) { history.replaceState(null, '', '#/' + route); }
function networkMessage(error, fallback) {
  const message = error.status === 0 ? fallback : error.message || fallback;
  return message;
}

async function refresh({background = false} = {}) {
  if (state.get().pending || identityChecking) return;
  identityChecking = true;
  const previous = state.get();
  const hadUser = !!previous.user;
  const quiet = background && ['anonymous','authenticated'].includes(previous.phase);
  const current = ++sequence;
  if (!quiet) state.update({phase: 'checking', pending: 'identity', user: null, error: '', notice: ''});
  try {
    const user = await authApi.current();
    if (current !== sequence) return;
    if (quiet && previous.user?.id === user.id && previous.user?.role === user.role && previous.user?.username === user.username) return;
    if (!location.hash || ['#/login','#/register'].includes(location.hash) || quiet && previous.user?.id !== user.id) replaceRoute(entryFor(user));
    state.update({phase: 'authenticated', user, pending: null});
  } catch (error) {
    if (current !== sequence) return;
    if (error.status === 401) {
      if (quiet && !hadUser) return;
      if (hadUser) workspace.reset();
      state.update({phase: 'anonymous', user: null, pending: null, notice: hadUser ? '登录已失效，请重新登录。' : ''});
    } else if (!quiet) state.update({phase: 'unavailable', user: null, pending: null, error: networkMessage(error, '无法取得身份核对结果，请检查连接后重试。')});
  } finally { identityChecking = false; }
}

async function login(username, password, remember) {
  if (state.get().pending) return;
  const current = ++sequence;
  state.update({phase: 'anonymous', user: null, username, pending: 'login', error: '', notice: ''});
  try {
    const user = await authApi.login(username, password, remember);
    if (current !== sequence) return;
    replaceRoute(entryFor(user));
    state.update({phase: 'authenticated', user, pending: null, error: '', notice: ''});
  } catch (error) {
    if (current !== sequence) return;
    const failedCredentials = error.status === 401 && error.code === 'AUTHENTICATION_FAILED';
    state.update({phase: failedCredentials ? 'anonymous' : 'unavailable', user: null, pending: null,
      error: networkMessage(error, '无法确认登录结果，请重新核对身份后再操作。')});
  }
}

async function signOut() {
  if (state.get().pending) return;
  const current = ++sequence;
  state.update({pending: 'logout', error: '', notice: ''});
  try {
    await authApi.logout();
    if (current !== sequence) return;
    replaceRoute('login');
    clearAutoLoginPreference();
    workspace.reset();
    state.update({phase: 'anonymous', user: null, username: '', pending: null, notice: '已退出登录。'});
  } catch (error) {
    if (current !== sequence) return;
    state.update({phase: 'unavailable', user: null, pending: null, error: networkMessage(error, '未能确认退出结果，请重新核对身份。')});
  }
}

async function register(username,password,passwordConfirmation) {
  if (state.get().pending) return;
  const current = ++sequence;
  state.update({pending:'register',error:'',notice:''});
  try {
    const result=await authApi.register(username,password,passwordConfirmation);
    if(current!==sequence)return;
    replaceRoute('login');
    state.update({phase:'anonymous',user:null,username:result.username,pending:null,error:'',notice:'注册成功，请使用新账号登录。'});
  } catch(error) {
    if(current!==sequence)return;
    state.update({pending:null,error:networkMessage(error,'未能确认注册结果，请先尝试登录；如未注册成功，再返回重试。')});
  }
}

const actions = Object.freeze({login, register, refresh});
function render(value) {
  const own = entryFor(value.user);
  const route = value.phase === 'authenticated' ? routeFor(location.hash, value.user) : value.phase;
  const name = route === 'anonymous' ? location.hash === '#/register' ? 'register' : 'login' : route;
  identity.textContent = value.user ? `${value.user.role === 'STAFF' ? '客服' : '客户'} · ${value.user.username}` : '';
  logout.hidden = !value.user;
  logout.disabled = !!value.pending;
  logout.textContent = value.pending === 'logout' ? '正在退出' : '退出登录';
  navigation.hidden = !value.user;
  navigation.replaceChildren();
  if(value.user){
    const items=own==='staff' ? [['tickets','工单工作台','▤']] : [['','服务概览','◈'],['assistant','AI 售后助手','✦'],['repair','在线报修','＋'],['devices','我的设备','▣'],['tickets','我的工单','▤']];
    navigation.append(el('p',own==='staff'?'客服工作空间':'客户服务','nav-label'));
    for(const [path,label,mark] of items){
      const a=link('',`#/${own}${path?'/'+path:''}`);const icon=el('span',mark,'nav-icon');icon.setAttribute('aria-hidden','true');a.append(icon,el('span',label));
      const current=location.hash.replace(/^#\//,'').split('/')[1]|| (own==='staff'?'tickets':'');
      if(current===path || (path==='tickets'&&current==='ticket'))a.setAttribute('aria-current','page');
      if(!path)a.id='home-link';if(path==='repair')a.id='repair-link';
      navigation.append(a);
    }
    navigation.append(el('p','设备咨询到维修跟进\n在这里，一次理清。','nav-footer'));
  }
  if (['login','register'].includes(name)) {
    if(entryName!==name) {
      entryView=(name==='login'?loginView:registerView)(value,actions);
      entry.replaceChildren(entryView.element);
    }
    entryView.update(value);
  } else {
    entryView=null;
    entry.replaceChildren(['customer','staff'].includes(name)?document.createDocumentFragment():statusView(value,actions,name==='denied'));
  }
  entryName=name;
  entry.dataset.view = name;
  entry.setAttribute('aria-busy', String(!!value.pending));
  const subroute=location.hash.replace(/^#\//,'').split('/').slice(1).join('/');
  const inWorkspace=['customer','staff'].includes(name);
  document.getElementById('scene').classList.toggle('workspace-open',inWorkspace);
  document.querySelector('.entry-region').hidden=inWorkspace;
  if(inWorkspace)workspace.open(value.user,subroute||(own==='staff'?'tickets':'home'));
  else workspace.suspend();
  if (name !== lastView && name !== 'checking') atmosphere?.revealEntry();
  lastView = name;
}

state.subscribe(render);
document.querySelector('.skip-link').addEventListener('click', event => {
  event.preventDefault();
  const target=document.getElementById('workspace');
  (target.hidden?entry:target).focus();
});
logout.addEventListener('click', signOut);
addEventListener('hashchange', () => render(state.get()));
addEventListener('focus', () => refresh({background:true}));
addEventListener('pageshow', event => { if (event.persisted) refresh({background:true}); });
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh({background:true}); });
render(state.get());
refresh();
startAtmosphere().then(value => { atmosphere = value; });
