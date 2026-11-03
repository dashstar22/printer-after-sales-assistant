// 复用 T017 的同源 Cookie、逐次 CSRF、超时与不自动重放写请求的实现。
import {request, ApiError} from '../repair/api.js';
export {ApiError};

function identity(value) {
  if (!value || !Number.isSafeInteger(value.id) || value.id <= 0 ||
      typeof value.username !== 'string' || !value.username.trim() ||
      !['CUSTOMER', 'STAFF', 'ADMIN'].includes(value.role)) {
    throw new ApiError(502, 'IDENTITY_INVALID', '暂时无法确认账号身份，请重新核对。');
  }
  return Object.freeze({id: value.id, username: value.username, role: value.role});
}

export const authApi = Object.freeze({
  async current() {
    const user = identity(await request('/api/auth/me'));
    let migration;
    try { migration = await request('/api/auth/migration'); }
    catch (error) {
      if (error.status === 403 && error.code === 'PASSWORD_CHANGE_REQUIRED')
        return Object.freeze({...user, emailVerified:false, passwordChangeRequired:true, passwordAuthenticated:true});
      throw error;
    }
    if (migration?.userId !== user.id || typeof migration.emailVerified !== 'boolean' || typeof migration.passwordChangeRequired !== 'boolean')
      throw new ApiError(502, 'IDENTITY_INVALID', '暂时无法确认邮箱验证状态，请重新核对。');
    return Object.freeze({...user, ...migration});
  },
  async login(username, password, remember = false) {
    // 登录表单只发送凭证；不发送 URL/存储/页面选择中的角色或用户标识。
    await request('/api/auth/login', {method: 'POST', form: {username, password, 'remember-me':String(remember)}});
    return this.current();
  },
  async logout() {
    const result = await request('/api/auth/logout', {method: 'POST'});
    if (result?.loggedOut !== true) throw new ApiError(502, 'LOGOUT_UNKNOWN', '未能确认退出结果，请重新核对身份。');
  },
  register: email => request('/api/auth/register', {method:'POST', body:{email}}),
  resetRequest: email => request('/api/auth/password-reset/request', {method:'POST', body:{email}}),
  bindRequest: (email,currentPassword) => request('/api/auth/email-bind/request', {method:'POST',body:{email,currentPassword}}),
  confirm: (purpose,body) => {
    const paths = {REGISTER:'/api/auth/register/confirm',RESET:'/api/auth/password-reset/confirm',INVITE:'/api/auth/invitations/accept',BIND:'/api/auth/email-bind/confirm'};
    if(!paths[purpose])throw new ApiError(400,'EMAIL_ACTION_INVALID','邮件操作无效，请重新申请。');
    return request(paths[purpose],{method:'POST',body});
  },
  changePassword: (currentPassword,newPassword,requestId) => request('/api/auth/password',{method:'POST',body:{currentPassword,newPassword,requestId}}),
});

export const serviceApi = Object.freeze({
  get: path => request(path),
  write: (path, body, method = 'POST') => request(path, {method, body}),
  upload: (path, multipart) => request(path,{method:'POST',multipart}),
  // Server turn budget is configurable up to 180s. Timeout never implies rollback.
  turn: (id, body) => request(`/api/conversations/${encodeURIComponent(id)}/messages`, {method: 'POST', body, timeoutMs: 195000}),
});
