// 后端 API 封装
const BASE = '/api';

async function req(path, opts = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new Error(data?.error || `HTTP ${res.status}`);
  }
  return data;
}

export const api = {
  listCases: () => req('/cases'),
  caseDetail: (no) => req(`/cases/${no}`),
  listCards: () => req('/cards'),
  reportLoss: (body) => req('/cases/report-loss', { method: 'POST', body }),
  issue: (no) => req(`/cases/${no}/issue-new-card`, { method: 'POST' }),
  transfer: (no) => req(`/cases/${no}/transfer`, { method: 'POST' }),
  retryClawback: (no) => req(`/cases/${no}/retry-clawback`, { method: 'POST' }),
  swipe: (body) => req('/gate/swipe', { method: 'POST', body }),
  receipt: (body) => req('/clearing/receipt', { method: 'POST', body }),
  recharge: (cardNo, amountCents) =>
    req(`/cards/${cardNo}/recharge`, { method: 'POST', body: { amountCents } }),
  armCardFactoryFailure: () => req('/cases/demo/arm-card-factory-failure', { method: 'POST' }),
  armTransferFailure: () => req('/cases/demo/arm-transfer-failure', { method: 'POST' }),
  reset: () => req('/demo/reset', { method: 'POST' })
};

export function yuan(cents) {
  if (cents == null) return '-';
  const neg = cents < 0;
  const a = Math.abs(cents);
  return (neg ? '-' : '') + (a / 100).toFixed(2);
}

export function fmtTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
