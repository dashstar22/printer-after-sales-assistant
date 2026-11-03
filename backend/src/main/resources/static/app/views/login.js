import {fragment, showText} from './shared.js';
import {loginPreferences,saveLoginPreferences} from '../login-preferences.js';

export function loginView(state, actions) {
  const view = fragment('login-view').firstElementChild;
  const form = view.querySelector('form');
  const username = view.querySelector('#username');
  const password = view.querySelector('#password');
  const submit = view.querySelector('#login-submit');
  const preferences=loginPreferences();
  const auto=view.querySelector('#auto-login');
  const help=document.createElement('details');help.className='auth-help';
  const summary=document.createElement('summary');summary.textContent='登录遇到问题？';
  const explanation=document.createElement('p');explanation.textContent='以前使用用户名登录？为原账号绑定邮箱后即可继续使用，原订单和工单会保留。';
  const legacy=document.createElement('a');legacy.textContent='使用原账号登录';legacy.href='#/legacy-login';
  help.append(summary,explanation,legacy);view.append(help);
  username.value = state.username || '';
  auto.checked=preferences.autoLogin;
  auto.addEventListener('change',()=>saveLoginPreferences(auto.checked));
  form.addEventListener('input',()=>showText(view.querySelector('#login-feedback'),''));
  let current = state;
  function update(value) {
    if(current.pending==='login'&&!value.pending)password.value='';
    current = value;
    showText(view.querySelector('#login-feedback'), value.error || value.notice);
    form.querySelectorAll('input,button').forEach(control => control.disabled = !!value.pending);
    submit.firstChild.textContent = value.pending === 'login' ? '正在登录' : '登录服务中心';
  }
  update(state);
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (current.pending || !form.reportValidity()) return;
    const name = username.value.trim();
    const credential = password.value;
    saveLoginPreferences(auto.checked);
    actions.login(name, credential,auto.checked);
  });
  return {element:view, update, dispose(){password.value='';}};
}
