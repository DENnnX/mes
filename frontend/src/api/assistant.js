export async function askMesAssistant(token, question, currentPath, previousTopics, signal) {
  const response = await fetch('/api/assistant/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: JSON.stringify({ question, currentPath, previousTopics }),
    signal
  });
  const result = await response.json().catch(() => null);
  if (!response.ok || !result) {
    throw new Error(result?.error || '暂时无法连接 MES 助手，请稍后重试。');
  }
  return result;
}
