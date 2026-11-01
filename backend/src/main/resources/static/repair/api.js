export class ApiError extends Error {
  constructor(status, code, message, traceId) { super(message); Object.assign(this, {status, code, traceId}); }
}
// 每次写入获取当前会话token；绝不自动重发业务写请求。
export async function request(path, {method = 'GET', body, form, timeoutMs = 15000} = {}) {
  const headers = {Accept: 'application/json'};
  if (method !== 'GET') {
    const csrf = await request('/api/auth/csrf');
    headers[csrf.headerName] = csrf.token;
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (form) headers['Content-Type'] = 'application/x-www-form-urlencoded';
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(path, {method, headers, credentials: 'same-origin', cache: 'no-store',
      signal: controller.signal, body: form ? new URLSearchParams(form) : body === undefined ? undefined : JSON.stringify(body)});
    const value = await response.json();
    if (!response.ok) throw new ApiError(response.status, value.code, value.message, value.traceId);
    return value;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(0, 'NETWORK_UNKNOWN', '未能取得响应。提交报修后请先查询原操作结果，勿重复新建。');
  } finally { clearTimeout(timer); }
}
