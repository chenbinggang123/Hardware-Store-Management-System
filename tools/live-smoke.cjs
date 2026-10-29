// Reads business data only. Login/logout write authentication audit records.
const https = require('node:https'), fs = require('node:fs');
const config = require('../miniprogram/config/cloud');
const base = process.env.HARDWARE_API_URL || config.baseURL + config.apiPrefix;
const index = process.argv.indexOf('--credentials');
const credentials = index >= 0 ? JSON.parse(fs.readFileSync(process.argv[index + 1], 'utf8').replace(/^\uFEFF/, '')) : { username: process.env.HARDWARE_USERNAME, password: process.env.HARDWARE_PASSWORD };
if (!credentials.username || !credentials.password) throw new Error('Set HARDWARE_USERNAME / HARDWARE_PASSWORD, or --credentials <local JSON>. Never commit credentials.');
if (new URL(base).protocol !== 'https:') throw new Error('Online smoke checks require HTTPS.');
function request(route, method = 'GET', data, token) {
  return new Promise((resolve, reject) => {
    const body = data === undefined ? null : JSON.stringify(data), start = Date.now();
    const r = https.request(base.replace(/\/$/, '') + route, { method, headers: {
      'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Length': Buffer.byteLength(body) } : {})
    } }, response => {
      let text = ''; response.on('data', d => text += d);
      response.on('end', () => { try { resolve({ status: response.statusCode, body: JSON.parse(text), ms: Date.now() - start }); } catch { reject(new Error('Non-JSON response: ' + route)); } });
    });
    r.setTimeout(20000, () => r.destroy(new Error('Request timeout')));
    r.on('error', reject); if (body) r.write(body); r.end();
  });
}
(async () => {
  let failures = 0;
  const login = await request('/auth/login', 'POST', { username: credentials.username, password: credentials.password });
  const token = login.body.data?.token;
  if (!token || !login.body.success) throw new Error('Login failed: ' + login.body.message);
  console.log('PASS login');
  try {
    for (const route of ['/auth/me','/products','/catalog','/catalog/suppliers','/suppliers','/customers','/inventories','/purchase-orders','/billing/records','/factory-ledgers','/agent/conversations','/excel-tasks','/reports/monthly-summary','/reports/daily']) {
      try { const r = await request(route, 'GET', undefined, token); const ok = r.status === 200 && r.body.success === true; if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'} ${route} HTTP ${r.status} ${r.ms}ms`); }
      catch (error) { failures++; console.log('FAIL ' + route + ': ' + error.message); }
    }
  } finally {
    const logout = await request('/auth/logout', 'POST', {}, token), expired = await request('/auth/me', 'GET', undefined, token);
    if (!logout.body.success || expired.status !== 401) failures++;
    console.log(`Logout ${logout.status}; expired token ${expired.status}`);
  }
  if (failures) throw new Error(`${failures} smoke checks failed`);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
