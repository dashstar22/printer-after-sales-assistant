// Only fixed UI strings become markup. Every API/model/user value is a text node.
export function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = String(text);
  if (className) node.className = className;
  return node;
}
export function button(text, action, className = 'secondary-button') {
  const node = el('button', text, className); node.type = 'button';
  node.addEventListener('click', action); return node;
}
export function link(text, href, className = '') { const a = el('a', text, className); a.href = href; return a; }
export function userLabel(user) {
  if(typeof user?.displayName==='string'&&user.displayName.trim())return user.displayName.trim();
  for(const key of ['displayEmail','email'])if(typeof user?.[key]==='string'&&user[key].trim())return user[key].trim();
  if(typeof user?.username==='string'&&!/^email_[a-f0-9]{32}$/i.test(user.username))return user.username;
  return {CUSTOMER:'客户',STAFF:'客服',ADMIN:'管理员'}[user?.role]||'用户';
}
export function field(label, control) {
  const wrapper = el('div', null, 'field'); const name = el('label', label);
  name.htmlFor = control.id; wrapper.append(name, control); return wrapper;
}
export function pairs(values) {
  const dl = el('dl', null, 'data-pairs');
  for (const [label, value] of values) dl.append(el('dt', label), el('dd', value ?? '—'));
  return dl;
}
export const stateNames = {PENDING:'待接单', IN_PROGRESS:'处理中', CLOSED:'已关闭'};
export function orderLabel(order) { return `${order.orderNo} · ${order.modelCode}`; }
export function orderInfo(order) { return `${order.brand} · 序列号 ${order.serialNo} · 购买日期 ${order.purchaseDate}`; }
export function feedback(error) {
  return `${error.status === 0 ? '未能取得响应，结果待核对。' : error.message || '暂时无法完成请求。'}${error.code ? ' ['+error.code+']' : ''}${error.traceId ? ' 追踪号：'+error.traceId : ''}`;
}
export const trim = text => text.replace(/^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/gu, '');
