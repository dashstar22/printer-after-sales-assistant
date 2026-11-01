import {request, ApiError} from './api.js';
const $ = id => document.getElementById(id);
const fields = ['symptom', 'extra', 'contact', 'contactMethod'];
const labels = ['故障描述', '补充信息', '联系人', '联系方式'];
const limits = [2000, 2000, 100, 200];
// 与Java Character.isWhitespace/isSpaceChar的边缘空白集合保持一致。
const normalized = text => text.replace(/^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/gu, '');
const key = 'printer.repair.locator.v1';
const state = {user: null, draft: null, confirmation: null, locator: null, dirty: false, busy: false, unknown: false, ticket: null};
const statusName = status => ({PENDING: '待接单', IN_PROGRESS: '处理中', CLOSED: '已关闭'}[status] || status);
function message(text, error = false) { $('message').textContent = text; $('message').dataset.error = String(error); }
function locator(value) {
  try { if (value) sessionStorage.setItem(key, JSON.stringify(value)); else sessionStorage.removeItem(key); }
  catch { throw new Error('无法保存本标签页的恢复定位，请允许会话存储后再提交。'); }
  state.locator = value;
}
function savedLocator() {
  try {
    const value = JSON.parse(sessionStorage.getItem(key));
    if (!value) return null;
    if (value.ownerId !== state.user.id) { locator(null); return null; }
    if (!Number.isSafeInteger(value.draftId) || value.draftId < 1) return null;
    return value;
  } catch { return null; }
}
function controls() {
  const customer = state.user?.role === 'CUSTOMER';
  $('login-panel').hidden = !!state.user;
  $('customer-panel').hidden = !customer;
  $('staff-panel').hidden = !state.user || customer;
  $('logout').hidden = !state.user;
  $('identity').textContent = state.user ? `${state.user.username} · ${customer ? '客户' : '客服'}` : '请登录';
  document.querySelectorAll('button').forEach(button => button.disabled = state.busy);
  $('reload-draft').disabled ||= !state.draft || state.unknown;
  $('new-draft').disabled ||= state.unknown || (!!state.locator?.operationId && !state.ticket);
  $('save').disabled ||= state.unknown || !!state.ticket;
  $('agreed').disabled = state.busy || !state.draft || state.dirty || state.unknown || !!state.ticket;
  $('confirm').disabled ||= $('agreed').disabled || !$('agreed').checked || !!state.confirmation;
  $('execute').disabled ||= !state.confirmation || state.dirty || state.unknown || !!state.ticket;
  $('recovery').hidden = !customer || !state.locator;
  $('retry').hidden = !state.confirmation || state.unknown || !!state.ticket || state.dirty;
  $('retry').disabled ||= $('execute').disabled;
  $('detail-panel').hidden = !state.ticket;
  $('draft-form').querySelectorAll('input,textarea,select').forEach(input => input.disabled = state.busy || state.unknown || !!state.ticket);
}
function blank() {
  Object.assign(state, {draft: null, confirmation: null, ticket: null, dirty: false, unknown: false});
  $('draft-form').reset(); $('agreed').checked = false;
  $('review').replaceChildren(); $('ticket-detail').replaceChildren(); $('events').replaceChildren(); $('tickets').replaceChildren();
  $('draft-meta').textContent = '尚未保存草稿';
  $('confirmation-meta').textContent = '尚未确认，不会创建工单。';
}
function pairs(target, values) {
  target.replaceChildren();
  for (const [label, value] of values) {
    const dt = document.createElement('dt'), dd = document.createElement('dd');
    dt.textContent = label; dd.textContent = String(value); target.append(dt, dd);
  }
}
async function run(action) {
  if (state.busy) return;
  state.busy = true; controls();
  try { await action(); }
  catch (error) {
    if (error.status === 401) {
      state.user = null; blank(); $('password').value = '';
      message('登录已失效，请重新登录。上次操作定位保留，登录后先查询结果。', true);
    } else {
      const guidance = ({DRAFT_CONFLICT: '请重新读取已保存内容，核对后再操作。', CONFIRMATION_CONFLICT: '确认已过期或内容已变化，请重新读取并核对。', DRAFT_ALREADY_SUBMITTED: '草稿已关联工单，请查询上次操作或工单列表。', CSRF_INVALID: '会话校验失败，请重新登录或再次主动操作。'})[error.code] || '';
      message(`${error.message} ${guidance}${error.code ? ` [${error.code}]` : ''}${error.traceId ? ` 追踪号：${error.traceId}` : ''}`, true);
    }
  } finally { state.busy = false; controls(); }
}
async function orders() {
  const page = await request('/api/orders');
  $('order').replaceChildren();
  for (const order of page.items) {
    const option = document.createElement('option'); option.value = order.id;
    option.textContent = `${order.orderNo} · ${order.modelCode}${order.orderDemo ? ' · 模拟订单' : ''}`;
    option.dataset.info = `序列号 ${order.serialNo} · 购买日期 ${order.purchaseDate}${order.modelDemo ? ' · 演示型号' : ''}`;
    $('order').append(option);
  }
  $('order-info').textContent = $('order').selectedOptions[0]?.dataset.info || '没有可报修的本人订单。';
}
function showDraft(draft) {
  state.draft = draft; state.dirty = false; state.confirmation = null; $('agreed').checked = false;
  $('order').value = draft.orderId;
  fields.forEach(field => $(field).value = draft[field]);
  $('draft-meta').textContent = `草稿 ${draft.id} · 已保存版本 ${draft.version}`;
  pairs($('review'), [['订单 / 型号', `${draft.order.orderNo} / ${draft.order.modelCode}${draft.order.orderDemo ? '（模拟订单）' : ''}`], ['序列号 / 购买日期', `${draft.order.serialNo} / ${draft.order.purchaseDate}`], ['草稿版本', draft.version], ...fields.map((field, i) => [labels[i], draft[field]])]);
  $('confirmation-meta').textContent = '请核对已保存内容，再主动确认。';
}
async function list() {
  const page = await request('/api/tickets'); $('tickets').replaceChildren();
  if (!page.items.length) { $('tickets').textContent = '尚无工单。保存并确认报修内容后即可提交。'; return; }
  for (const item of page.items) {
    const li = document.createElement('li'), button = document.createElement('button');
    button.className = 'secondary number'; button.textContent = `${item.ticketNo} · ${statusName(item.status)} · ${item.modelCode}`;
    button.addEventListener('click', () => run(() => detail(item.id))); li.append(button); $('tickets').append(li);
  }
}
async function detail(id) {
  const ticket = await request(`/api/tickets/${id}`); state.ticket = ticket;
  $('confirmation-meta').textContent = '已读取工单结果，处理进度请以下方工单为准。';
  pairs($('ticket-detail'), [['工单编号', ticket.ticketNo], ['状态', statusName(ticket.status)], ['订单 / 型号', `${ticket.order.orderNo} / ${ticket.order.modelCode}`], ...fields.map((field, i) => [labels[i], ticket[`snapshot${field[0].toUpperCase()}${field.slice(1)}`]])]);
  $('events').replaceChildren();
  for (const event of ticket.events) {
    const li = document.createElement('li');
    li.textContent = `${event.createdAt} · ${({CREATED: '已创建', ACCEPTED: '已接单', NOTE: '处理记录', CLOSED: '已关闭'})[event.eventType] || event.eventType}\n${event.note}`;
    $('events').append(li);
  }
}
async function enter(user) {
  state.user = user; blank(); state.locator = savedLocator();
  if (user.role === 'CUSTOMER') {
    await orders(); await list();
    state.unknown = !!state.locator?.operationId;
    message(state.locator ? '发现上次报修定位，请先点击查询 / 读取。' : '请选择本人订单并填写报修信息。');
  } else message('已使用服务端确认的客服身份登录。');
}
$('login-form').addEventListener('submit', event => {event.preventDefault(); run(async () => {
  const password = $('password').value; $('password').value = '';
  const user = await request('/api/auth/login', {method: 'POST', form: {username: $('username').value, password}});
  await enter(user);
});});
$('logout').addEventListener('click', () => run(async () => {
  await request('/api/auth/logout', {method: 'POST'}); locator(null); state.user = null; blank(); message('已退出登录。');
}));
$('draft-form').addEventListener('input', () => { state.dirty = true; $('agreed').checked = false; controls(); });
$('order').addEventListener('change', () => $('order-info').textContent = $('order').selectedOptions[0]?.dataset.info || '');
$('agreed').addEventListener('change', controls);
$('draft-form').addEventListener('submit', event => {event.preventDefault(); run(async () => {
  const body = {orderId: Number($('order').value)};
  for (let i = 0; i < fields.length; i++) {
    const text = $(fields[i]).value;
    if (!normalized(text) || [...normalized(text)].length > limits[i]) throw new Error(`${labels[i]}为必填，去除首尾空白后最多 ${limits[i]} 字。`);
    body[fields[i]] = text;
  }
  if (!body.orderId) throw new Error('请选择本人订单。');
  if (state.draft) body.expectedVersion = state.draft.version;
  const draft = await request(state.draft ? `/api/repair-drafts/${state.draft.id}` : '/api/repair-drafts', {method: state.draft ? 'PUT' : 'POST', body});
  showDraft(draft); locator({ownerId: state.user.id, draftId: draft.id}); message('草稿已保存。请核对右侧完整内容并明确确认。');
});});
$('reload-draft').addEventListener('click', () => run(async () => {
  showDraft(await request(`/api/repair-drafts/${state.draft.id}`)); message('已重新读取服务端草稿，请核对后确认。');
}));
$('confirm').addEventListener('click', () => run(async () => {
  if (!$('agreed').checked || state.dirty || !state.draft) return;
  const confirmation = await request(`/api/repair-drafts/${state.draft.id}/confirmations`, {method: 'POST', body: {confirmed: true, expectedVersion: state.draft.version}});
  locator({ownerId: state.user.id, draftId: confirmation.draftId, confirmationId: confirmation.confirmationId, operationId: confirmation.operationId, draftVersion: confirmation.draftVersion});
  state.confirmation = confirmation;
  $('confirmation-meta').textContent = `内容已确认，有效期至 ${confirmation.expiresAt}。尚未提交。`;
  if (confirmation.ticketId) { await detail(confirmation.ticketId); message('该报修已提交，已读取原工单。'); }
  else message('内容已确认。点击“提交已确认报修”创建工单。');
}));
async function execute() {
  const located = state.locator;
  if (!located?.operationId || !state.confirmation || state.dirty || state.unknown) return;
  // 执行前再次确保定位可续用，网络未知后不释放原操作。
  locator(located); state.unknown = true; controls();
  const result = await request(`/api/ticket-operations/${located.operationId}/execute`, {method: 'POST', body: {confirmationId: located.confirmationId, draftId: located.draftId, expectedVersion: located.draftVersion}});
  await detail(result.ticket.id); state.unknown = false; await list(); message('报修已提交，工单编号和快照以以下服务端结果为准。');
}
$('execute').addEventListener('click', () => run(execute)); $('retry').addEventListener('click', () => run(execute));
$('recover').addEventListener('click', () => run(async () => {
  const located = state.locator;
  if (!located) return;
  if (!located.operationId) { showDraft(await request(`/api/repair-drafts/${located.draftId}`)); state.unknown = false; message('已恢复服务端草稿，请核对后确认。'); return; }
  let result;
  try { result = await request(`/api/ticket-operations/${located.operationId}`); }
  catch (error) {
    if (error.code !== 'CONFIRMATION_CONFLICT') throw error;
    const latest = await request(`/api/repair-drafts/${located.draftId}`);
    if (latest.version === located.draftVersion) throw error;
    showDraft(latest); state.unknown = false;
    locator({ownerId: state.user.id, draftId: latest.id});
    message('草稿版本已变化，原确认不再有效。请核对最新内容并重新确认。', true); return;
  }
  if (result.status === 'SUCCEEDED') { await detail(result.ticket.id); state.unknown = false; await list(); message('已恢复原操作的工单结果，没有再次建单。'); return; }
  showDraft(await request(`/api/repair-drafts/${located.draftId}`)); state.unknown = false;
  if (result.status === 'EXPIRED') { locator({ownerId: state.user.id, draftId: located.draftId}); message('原确认已过期且未建单，请重新核对并确认。', true); }
  else {
    state.confirmation = result; $('agreed').checked = true;
    $('confirmation-meta').textContent = `原操作尚未执行，有效期至 ${result.expiresAt}。`;
    message('已查询原操作：尚未建单，可使用原操作主动重试提交。');
  }
}));
$('new-draft').addEventListener('click', () => run(async () => { locator(null); blank(); await orders(); await list(); message('请填写新的报修草稿。'); }));
$('refresh-list').addEventListener('click', () => run(async () => { await list(); message('工单列表已刷新。'); }));
$('refresh-detail').addEventListener('click', () => run(async () => { await detail(state.ticket.id); message('已读取最新工单状态和处理记录。'); }));
run(async () => {
  try { await enter(await request('/api/auth/me')); }
  catch (error) {
    if (error.status !== 401) throw error;
    message('请使用预置账号登录售后服务。');
  }
});
