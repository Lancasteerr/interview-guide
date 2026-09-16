import { createHash } from 'node:crypto';
import { createServer as createHttpServer, type Server } from 'node:http';
import type { AddressInfo, Socket } from 'node:net';
import { expect, test } from '@playwright/test';
import { createServer, type ViteDevServer } from 'vite';

let upstream: Server;
let devServer: ViteDevServer;
let baseURL: string;
const sockets = new Set<Socket>();
let handshake: { path?: string; host?: string; origin?: string } | undefined;

test.beforeAll(async () => {
  upstream = createHttpServer((req, res) => {
    req.resume();
    const data = req.url?.includes('/skills') || req.url?.endsWith('/messages') ? [] : {
      sessionId: 42, currentPhase: 'TECH', status: 'IN_PROGRESS', plannedDuration: 15,
      // 旧后端的固定地址也不应再让浏览器绕过当前应用代理。
      webSocketUrl: 'ws://localhost:8080/ws/voice-interview/42',
    };
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ code: 200, message: 'success', data }));
  });
  // 最小真实握手服务器：校验转发后的同源关系并发送一条 ASR 就绪帧。
  // 不替换浏览器 WebSocket，确保实际经过 Vite 的 upgrade 转发。
  upstream.on('upgrade', (req, socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => socket.destroy());
    handshake = { path: req.url, host: req.headers.host, origin: req.headers.origin };
    if (req.headers.origin !== `http://${req.headers.host}`) {
      socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
      return;
    }
    const accept = createHash('sha1')
      .update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
      + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);
    const payload = Buffer.from(JSON.stringify({ type: 'control', action: 'asr_ready' }));
    socket.write(Buffer.concat([Buffer.from([0x81, payload.length]), payload]));
  });
  await new Promise<void>(resolve => upstream.listen(0, '127.0.0.1', resolve));
  const target = `http://127.0.0.1:${(upstream.address() as AddressInfo).port}`;
  devServer = await createServer({
    configFile: './vite.config.ts', logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0, open: false,
      proxy: { '/api': { target }, '/ws': { target } },
    },
  });
  await devServer.listen();
  baseURL = `http://127.0.0.1:${(devServer.httpServer!.address() as AddressInfo).port}`;
});

test.afterAll(async () => {
  for (const socket of sockets) socket.destroy();
  await devServer?.close();
  if (upstream?.listening) await new Promise<void>(resolve => upstream.close(() => resolve()));
});

test('语音面试通过当前应用地址完成真实 WebSocket 握手', async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', route => route.abort());
  await page.goto(baseURL + '/voice-interview?skillId=java-backend&duration=15');
  await expect(page.getByTestId('voice-recorder-toggle')).toBeEnabled();
  expect(handshake).toEqual({
    path: '/ws/voice-interview/42', host: new URL(baseURL).host, origin: baseURL,
  });
  await expect(page.getByText('连接已断开，请刷新页面重试', { exact: true })).not.toBeVisible();
  await page.goto('about:blank');
});

test('恢复语音面试也使用相同的 WebSocket 代理入口', async ({ page }) => {
  handshake = undefined;
  await page.route('https://cdn.jsdelivr.net/**', route => route.abort());
  await page.addInitScript(() => {
    window.history.replaceState({ usr: { voiceSessionId: 42 }, key: 'resume-test', idx: 0 }, '');
  });
  await page.goto(baseURL + '/voice-interview');
  await expect(page.getByTestId('voice-recorder-toggle')).toBeEnabled();
  expect(handshake).toEqual({
    path: '/ws/voice-interview/42', host: new URL(baseURL).host, origin: baseURL,
  });
  await page.goto('about:blank');
});
