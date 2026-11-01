// 身份只在内存中保留；刷新后重新向服务端核对，不读取浏览器存储。
export function createState() {
  let value = Object.freeze({phase: 'checking', user: null, pending: null, username: '', error: '', notice: ''});
  const listeners = new Set();
  return Object.freeze({
    get: () => value,
    update(patch) {
      value = Object.freeze({...value, ...patch});
      listeners.forEach(listener => listener(value));
    },
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
  });
}

export function entryFor(user) {
  return user?.role === 'CUSTOMER' ? 'customer' : user?.role === 'STAFF' ? 'staff' : null;
}

export function routeFor(hash, user) {
  const own = entryFor(user);
  if (!own) return null;
  const requested = hash.replace(/^#\/?/, '').split(/[?&]/, 1)[0];
  if (!requested || requested === 'login') return own;
  return requested === own || (own === 'customer' && /^customer\/(assistant|repair|devices|tickets|ticket)(\/(\d+|new))?$/.test(requested)) ||
    (own === 'staff' && /^staff\/(tickets|ticket)(\/\d+)?$/.test(requested)) ? own : 'denied';
}
