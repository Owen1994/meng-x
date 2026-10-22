const TOKEN_KEY = 'mengx_token';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function saveToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

/** 默认记住密码：登录成功后保存，下次进登录页自动回填（信任圈产品，明文存本机） */
const CREDENTIALS_KEY = 'mengx_credentials';

export function saveCredentials(phone, password) {
  localStorage.setItem(CREDENTIALS_KEY, JSON.stringify({ phone, password }));
}

export function loadCredentials() {
  try {
    return JSON.parse(localStorage.getItem(CREDENTIALS_KEY)) || null;
  } catch {
    return null;
  }
}

/** 统一 API 调用：自动携带 token，非 2xx 抛出带后端 message 的 Error；signal 可选（用于请求超时中止） */
export async function api(path, { method = 'GET', body, signal } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const resp = await fetch(path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal,
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) {
    throw new Error(data.message || `请求失败（${resp.status}）`);
  }
  return data;
}

/** 解析 SSE 流：事件以空行分隔，多行 data: 按规范拼接 */
async function consumeSse(resp, onEvent) {
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const parts = buf.split('\n\n');
    buf = parts.pop();
    for (const part of parts) {
      const dataLines = part
        .split('\n')
        .filter((l) => l.startsWith('data:'))
        .map((l) => l.replace(/^data:? ?/, ''));
      if (!dataLines.length) continue;
      onEvent(JSON.parse(dataLines.join('\n')));
    }
  }
}

/**
 * 百晓通流式访谈：POST /api/idea/chat/stream（JWT）
 * mode：host=仙官本色 / law=法学助手（消息级路由）
 * greet：true=法学助手出场触发（无用户消息，由后端注入触发指令，开场白不落 user 行）
 * 事件 {type, content}：thinking 掐指一算中 | design 营造图 JSON | chunk 增量文本 | card 功能卡 DTO JSON | done 结束 | error 失败
 */
export async function streamIdeaChat(sessionId, messages, mode, onEvent, greet = false) {
  const headers = { 'Content-Type': 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const resp = await fetch('/api/idea/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({ sessionId, messages, mode, greet }),
  });
  if (!resp.ok) {
    const data = await resp.json().catch(() => ({}));
    throw new Error(data.message || `请求失败（${resp.status}）`);
  }
  await consumeSse(resp, onEvent);
}
