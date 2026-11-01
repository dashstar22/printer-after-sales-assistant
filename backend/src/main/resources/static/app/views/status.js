import {fragment, showText} from './shared.js';
import {entryFor} from '../state.js';

export function statusView(state, actions, denied = false) {
  const view = fragment('message-view');
  const checking = state.phase === 'checking';
  view.querySelector('[data-heading]').textContent = denied ? '当前账号不能进入此页面' : checking ? '正在核对登录状态…' : '暂时无法核对身份';
  view.querySelector('[data-message]').textContent = denied ? '此入口不对当前账号开放，请返回自己的服务入口。' : checking ? '请稍候，服务入口会在身份确认后显示。' : '重新核对前，不会展示账号的服务入口。';
  showText(view.querySelector('[data-detail]'), state.error);
  const retry = view.querySelector('[data-retry]');
  retry.hidden = checking || denied;
  retry.disabled = !!state.pending;
  retry.addEventListener('click', actions.refresh);
  if (denied) {
    const home = view.querySelector('[data-home]');
    home.hidden = false;
    home.href = '#/' + entryFor(state.user);
  }
  return view;
}
