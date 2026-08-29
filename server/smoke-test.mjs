'use strict';

/**
 * 后端冒烟测试：启动服务器后执行 `node smoke-test.mjs`。
 * 覆盖：创建/加入家庭、WebSocket 连接、位置上报广播、实时刷新指令、成员列表、移出权限。
 * 使用 Node 内置 fetch 与 WebSocket（Node >= 22）。
 */

const BASE = process.env.BASE || 'http://127.0.0.1:3000';
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws';

let failed = 0;

function check(name, cond, detail) {
  if (cond) {
    console.log('PASS', name);
  } else {
    console.log('FAIL', name, detail || '');
    failed++;
  }
}

async function post(path, body) {
  const r = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: r.status, body: await r.json() };
}

async function get(path) {
  const r = await fetch(BASE + path);
  return { status: r.status, body: await r.json() };
}

function connect(deviceId, familyId, name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS_URL}?deviceId=${deviceId}&familyId=${familyId}&name=${name}`);
    const inbox = [];
    const timer = setTimeout(() => reject(new Error('ws open timeout')), 5000);
    ws.onopen = () => {
      clearTimeout(timer);
      resolve({ ws, inbox });
    };
    ws.onmessage = (e) => inbox.push(JSON.parse(e.data));
    ws.onerror = (e) => {
      clearTimeout(timer);
      reject(e);
    };
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- 1. 创建/加入家庭 ----------
const dev1 = 'd1-' + Date.now();
const dev2 = 'd2-' + Date.now();

const fam = await post('/api/family/create', { deviceId: dev1, name: '爸爸' });
check('create family', fam.status === 200 && fam.body.familyId && /^\d{6}$/.test(fam.body.code || ''), JSON.stringify(fam.body));

// 加入家庭需群主同意：dev2 提交申请 -> pending
const joined = await post('/api/family/join', { code: fam.body.code, deviceId: dev2, name: '妈妈' });
check('join request pending', joined.status === 200 && joined.body.status === 'pending' && joined.body.requestId, JSON.stringify(joined.body));

// 群主 dev1 同意加入
const approved = await post('/api/family/join/handle', {
  familyId: fam.body.familyId, ownerDeviceId: dev1, requestId: joined.body.requestId, approve: true,
});
check('owner approve join', approved.status === 200 && approved.body.status === 'ok', JSON.stringify(approved.body));

// dev2 轮询直到群主同意
let joinStatus = {};
for (let i = 0; i < 10; i++) {
  joinStatus = await get(`/api/family/join/status?requestId=${joined.body.requestId}&deviceId=${dev2}`);
  if (joinStatus.body && joinStatus.body.status === 'approved') break;
  await sleep(200);
}
check('join approved status', joinStatus.body && joinStatus.body.status === 'approved'
  && joinStatus.body.familyId === fam.body.familyId, JSON.stringify(joinStatus.body));

// ---------- 2. WebSocket 连接 ----------
const c1 = await connect(dev1, fam.body.familyId, '爸爸');
const c2 = await connect(dev2, fam.body.familyId, '妈妈');
await sleep(300);

check('ws hello', c1.inbox.some((m) => m.type === 'hello'), JSON.stringify(c1.inbox));
// c1 先连接，c2 后连接：c1 应收到 c2 的上线广播
check('ws member online broadcast', c1.inbox.some((m) => m.type === 'member-status' && m.deviceId === dev2 && m.online === true), JSON.stringify(c1.inbox));

// ---------- 3. 位置上报 -> 广播 ----------
const now = Date.now();
const rep = await post('/api/location/report', {
  deviceId: dev2, familyId: fam.body.familyId,
  lat: 39.915, lng: 116.404, accuracy: 30, ts: now,
});
check('report ok', rep.status === 200 && rep.body.status === 'ok', JSON.stringify(rep.body));
await sleep(400);
check('ws location-update broadcast', c1.inbox.some((m) => m.type === 'location-update' && m.deviceId === dev2 && m.lat === 39.915), JSON.stringify(c1.inbox));

// ---------- 4. 实时刷新指令 ----------
const req = await post('/api/location/request', { familyId: fam.body.familyId, requesterId: dev1, targetDeviceId: dev2 });
check('request ok', req.status === 200 && req.body.status === 'ok', JSON.stringify(req.body));
await sleep(400);
check('ws report-now to target', c2.inbox.some((m) => m.type === 'report-now' && m.from === dev1), JSON.stringify(c2.inbox));

// ---------- 5. 成员列表 ----------
const list = await fetch(BASE + '/api/family/members?familyId=' + fam.body.familyId);
const members = await list.json();
check('members list with location', list.status === 200 && members.length === 2
  && members.some((m) => m.deviceId === dev2 && m.location && m.location.lat === 39.915), JSON.stringify(members));

// ---------- 6. 移出成员权限 ----------
const r4 = await post('/api/family/member/remove', { familyId: fam.body.familyId, ownerDeviceId: dev1, targetDeviceId: dev2 });
check('owner remove ok', r4.status === 200, JSON.stringify(r4.body));

const r5 = await post('/api/family/member/remove', { familyId: fam.body.familyId, ownerDeviceId: dev2, targetDeviceId: dev1 });
check('non-owner rejected 403', r5.status === 403, JSON.stringify(r5.body));

await sleep(300);
check('ws member-removed broadcast', c1.inbox.some((m) => m.type === 'member-removed' && m.deviceId === dev2), JSON.stringify(c1.inbox));

// ---------- 收尾 ----------
c1.ws.close();
c2.ws.close();
console.log(failed === 0 ? '== ALL TESTS PASSED ==' : `== ${failed} TESTS FAILED ==`);
process.exit(failed === 0 ? 0 : 1);
