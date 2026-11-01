import {fragment, showText} from './shared.js';
import {loginPreferences,saveLoginPreferences} from '../login-preferences.js';

export function loginView(state, actions) {
  const view = fragment('login-view').firstElementChild;
  const form = view.querySelector('form');
  const username = view.querySelector('#username');
  const password = view.querySelector('#password');
  const submit = view.querySelector('#login-submit');
  const preferences=loginPreferences();
  const remember=view.querySelector('#remember-account');
  const auto=view.querySelector('#auto-login');
  username.value = state.username || preferences.username;
  remember.checked=preferences.remember;auto.checked=preferences.autoLogin;
  remember.addEventListener('change',()=>saveLoginPreferences(username.value.trim(),remember.checked,auto.checked));
  auto.addEventListener('change',()=>saveLoginPreferences(username.value.trim(),remember.checked,auto.checked));
  form.addEventListener('input',()=>showText(view.querySelector('#login-feedback'),''));
  let current = state;
  function update(value) {
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
    password.value = '';
    saveLoginPreferences(name,remember.checked,auto.checked);
    actions.login(name, credential,auto.checked);
  });
  return {element:view, update};
}
