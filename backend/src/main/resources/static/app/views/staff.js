import {fragment, bindRefresh} from './shared.js';
export function staffView(state, actions) {
  const view = fragment('staff-view');
  view.querySelector('[data-username]').textContent = state.user.username;
  bindRefresh(view, actions.refresh, state.pending);
  return view;
}
