<script>
  import CaseBoard from './CaseBoard.svelte';
  import { api } from '../api.js';

  let { detail: d, refresh, showToast, selectCase } = $props();

  let cardNo = $state('6200000000001001');
  let idNo = $state('110101199001011234');
  let agentId = $state('A001');
  let busy = $state(false);

  async function run(fn, okMsg) {
    busy = true;
    try {
      const r = await fn();
      if (r && r.ok === false) {
        showToast('操作失败但已留痕：' + r.error, 'error');
      } else {
        showToast(okMsg, 'ok');
      }
      await refresh();
    } catch (e) {
      showToast(e.message, 'error');
    } finally {
      busy = false;
    }
  }

  const report = async () => {
    busy = true;
    try {
      const r = await api.reportLoss({ cardNo: cardNo.trim(), idNo: idNo.trim(), agentId: agentId.trim() });
      showToast('身份核验通过，旧卡已停用', 'ok');
      await refresh();
      selectCase?.(r.caseNo); // 自动跳转到新建/复活的案件
    } catch (e) {
      showToast(e.message, 'error');
    } finally {
      busy = false;
    }
  };
  const issue = () => run(() => api.issue(d.caseNo), '制卡请求完成');
  const transfer = () => run(() => api.transfer(d.caseNo), '余额转移处理完成');
  const retry = () => run(() => api.retryClawback(d.caseNo), '已重试挂账追扣');
</script>

<div class="card-panel">
  <h3>🧑‍💼 客服操作台 · 当前案件 {d.caseNo} <span class="badge {d.status}">{d.statusLabel}</span></h3>

  <div class="form-grid">
    <div>
      <label>旧卡号</label>
      <input bind:value={cardNo} placeholder="16 位卡号" />
    </div>
    <div>
      <label>乘客证件号（用于身份核验）</label>
      <input bind:value={idNo} placeholder="身份证号" />
    </div>
    <div>
      <label>客服工号</label>
      <input bind:value={agentId} />
    </div>
    <div>
      <button class="btn" disabled={busy} onclick={report}>① 核验身份并挂失停用旧卡</button>
    </div>
  </div>

  <div class="pill-row" style="margin-top:14px">
    <button class="btn ghost" disabled={busy || (d.status !== 'OLD_CARD_BLOCKED' && d.status !== 'FAILED') || d.newCard}
      onclick={issue}>② 制发新卡（一张旧卡仅一张有效新卡）</button>
    <button class="btn" disabled={busy || (d.status !== 'READY_TO_TRANSFER' && d.status !== 'FAILED')}
      onclick={transfer}>③ 执行余额转移（等额配对）</button>
    <button class="btn ghost" disabled={busy} onclick={retry}>↻ 重试晚到差额追扣</button>
  </div>
  <div class="muted">
    规则：挂失后旧卡立即停用；在途消费未清算完不能转账；重复挂失/重复制卡/重复转账都会被拒绝。
    制卡或转账失败时，案件置 FAILED 并保留全部处理记录，可再次点击按钮重试。
  </div>
</div>

<CaseBoard detail={d} mode="agent" />
