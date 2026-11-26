import { parseFileCell } from '@/utils/formatters';

export async function rpcCall(model, service, args = {}, endpoint = '/rbac_tenant_root/rpc/service', tokenOverride = null) {
  const headers = { 'Content-Type': 'application/json' };
  const token = tokenOverride !== null ? tokenOverride : localStorage.getItem('lc_token');
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  const payload = {
    jsonrpc: '2.0',
    id: 'ui-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
    method: 'service',
    params: {
      app: 'lowcode_studio',
      tag: 'master',
      model,
      service,
      context: { lang: 'zh-CN' },
      args
    }
  };
  const res = await fetch(endpoint, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  if (data.error) {
    const details = data.error.data?.details;
    const msg = data.error.message + (Array.isArray(details) && details.length ? ' (' + details.join('; ') + ')' : '');
    const err = new Error(msg);
    err.rpcError = data.error;
    throw err;
  }
  return data.result;
}

export async function uploadFile(file) {
  const fd = new FormData();
  fd.append('file', file);
  const headers = {};
  const token = localStorage.getItem('lc_token');
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  const resp = await fetch('/api/files/upload', { method: 'POST', headers, body: fd });
  const json = await resp.json();
  if (!resp.ok || json.error) {
    throw new Error(json.error || '上传失败');
  }
  return json;
}

export async function downloadFile(value) {
  const file = parseFileCell(value);
  if (!file) return;
  const headers = {};
  const token = localStorage.getItem('lc_token');
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  const response = await fetch('/api/files/' + encodeURIComponent(file.fileId), { headers });
  if (!response.ok) {
    throw new Error('附件不存在、未授权或登录已过期');
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = file.fileName;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
