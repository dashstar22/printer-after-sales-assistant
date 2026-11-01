import {fragment, bindRefresh} from './shared.js';
export function customerView(state, actions) {
  const view = fragment('customer-view');
  view.querySelector('[data-username]').textContent = state.user.username;
  bindRefresh(view, actions.refresh, state.pending);
  return view;
}
