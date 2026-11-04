// 에뮬레이터 API 호출을 브라우저에서 수행하며 HAR로 기록한다 (REA inspect_web_network_capture 입력 형식).
// 사용: EMU_BASE=http://127.0.0.1:8090 EMU_USERNAME=... EMU_PASSWORD=... node capture_har.mjs out.har
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const base = process.env.EMU_BASE ?? 'http://127.0.0.1:8090';
const out = process.argv[2] ?? 'capture.har';
const browser = await chromium.launch();
const context = await browser.newContext({ recordHar: { path: out, content: 'embed' } });
const page = await context.newPage();
await page.goto(`${base}/emulator/state`);

await page.evaluate(async ({ user, pass }) => {
  const j = (r) => r.json();
  const t = (r) => r.text();   // 본문을 끝까지 읽어야 HAR 에 응답이 기록된다
  const tok = await fetch('/api/v1/auth/token', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: pass }) }).then(j);
  const auth = { Authorization: `Bearer ${tok.accessToken}` };
  await fetch('/api/v1/me', { headers: auth }).then(t);
  await fetch('/api/v1/sensors/current', { headers: auth }).then(t);
  const from = new Date(Date.now() - 3 * 3600e3).toISOString();
  let cursor = null;
  do {
    const q = `/api/v1/glucose?from=${encodeURIComponent(from)}&limit=12` + (cursor ? `&cursor=${cursor}` : '');
    const page = await fetch(q, { headers: auth }).then(j);
    cursor = page.nextCursor;
  } while (cursor);
  await fetch('/api/v1/glucose/latest', { headers: auth }).then(t);
  await fetch(`/hc/v1/blood-glucose-records?from=${encodeURIComponent(from)}`, { headers: auth }).then(t);
  await fetch('/api/v1/me').then(t);                                   // 토큰 없이 → 401 관찰
  await fetch('/api/v1/auth/refresh', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: tok.refreshToken }) }).then(t);
}, { user: process.env.EMU_USERNAME, pass: process.env.EMU_PASSWORD });

await context.close();
await browser.close();
console.log(`HAR written: ${out}`);
