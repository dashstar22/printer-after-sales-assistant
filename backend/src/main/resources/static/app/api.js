// 复用 T017 的同源 Cookie、逐次 CSRF、超时与不自动重放写请求的实现。
import {request, ApiError} from '../repair/api.js';
export {ApiError};

function identity(value) {
  if (!value || !Number.isSafeInteger(value.id) || value.id <= 0 ||
      typeof value.username !== 'string' || !value.username.trim() ||
      !['CUSTOMER', 'STAFF'].includes(value.role)) {
    throw new ApiError(502, 'IDENTITY_INVALID', '暂时无法确认账号身份，请重新核对。');
  }
  return Object.freeze({id: value.id, username: value.username, role: value.role});
}

export const authApi = Object.freeze({
  async current() { return identity(await request('/api/auth/me')); },
  async login(username, password, remember = false) {
    // 登录表单只发送凭证；不发送 URL/存储/页面选择中的角色或用户标识。
    await request('/api/auth/login', {method: 'POST', form: {username, password, 'remember-me':String(remember)}});
    return this.current();
  },
  async logout() {
    const result = await request('/api/auth/logout', {method: 'POST'});
    if (result?.loggedOut !== true) throw new ApiError(502, 'LOGOUT_UNKNOWN', '未能确认退出结果，请重新核对身份。');
  },
  async register(username, password, passwordConfirmation) {
    const result = await request('/api/auth/register', {method:'POST', body:{username,password,passwordConfirmation}});
    if (result?.registered !== true || typeof result.username !== 'string') throw new ApiError(502,'REGISTRATION_UNKNOWN','未能确认注册结果，请先尝试登录。');
    return result;
  },
});

export const serviceApi = Object.freeze({
  get: path => request(path),
  write: (path, body, method = 'POST') => request(path, {method, body}),
  // Server turn budget is configurable up to 180s. Timeout never implies rollback.
  turn: (id, body) => request(`/api/conversations/${encodeURIComponent(id)}/messages`, {method: 'POST', body, timeoutMs: 195000}),
});
