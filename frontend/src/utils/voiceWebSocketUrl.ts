/** 与 REST API 使用同一入口，避免将服务器的 localhost 地址当成浏览器地址。 */
export function buildVoiceWebSocketUrl(sessionId: number, apiBaseUrl: string, pageUrl: string): string {
  const apiUrl = new URL(apiBaseUrl || '/', pageUrl);
  const socketUrl = new URL(apiUrl);
  socketUrl.pathname = `${apiUrl.pathname.replace(/\/+$/, '')}/ws/voice-interview/${sessionId}`;
  socketUrl.search = '';
  socketUrl.hash = '';
  socketUrl.protocol = apiUrl.protocol === 'https:' ? 'wss:' : 'ws:';
  return socketUrl.toString();
}
