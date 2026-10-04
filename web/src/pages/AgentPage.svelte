<script>
  import { onMount } from 'svelte';
  import { api, fmtAmt, fmtTime } from '../lib/api.js';
  import { showToast } from '../lib/store.js';
  import TxnTable from '../components/TxnTable.svelte';
  import Timeline from '../components/Timeline.svelte';

  export let onGoSim;

  let cards = [];
  let cases = [];
  let selected = null;
  let detail = null;

  let fCard = 'C1001';
  let fId = '110101199001011234';
  let fFail = false;
  let fReceipt = '';

  async function refreshAll() {
    cards = await api.get('/dev/cards');
    cases = await api.get('/cs/cases');
    if (selected) {
      try { detail = await api.get('/cs/cases/' + selected); } catch { detail = null; }
    }
  }

  onMount(refreshAll);

  async function act(desc, fn) {
    try {
      const r = await fn();
      showToast(desc + '成功');
      if (r?.id && typeof r.id === 'string' && r.id.startsWith('RPL-')) {
        selected = r.id;
      }
      await refreshAll();
    } catch (e) {
      showToast(desc + '失败：' + e.message, 'error');
      await refreshAll();
    }
  }

  const report = () => act('核身挂失', () =>
    api.post('/cs/report-lost', { cardId: fCard.trim(), idNumber: fId.trim() }));

  const reReport = () => act('新卡再挂失', () =>
    api.post('/cs/re-report-lost', { cardId: fCard.trim(), idNumber: fId.trim() }));

  const issue = () => act('制新卡', () =>
    api.post('/cs/issue', { replacementId: selected, simulateFailure: fFail }));

  const transfer = () => act('手动转移余额', () =>
    api.post('/cs/transfer', {
      replacementId: selected,
      receiptId: fReceipt.trim() || ('TR-MANUAL-' + Date.now())
    }));

  async function selectCase(id) {
    selected = id;
    detail = await api.get('/cs/cases/' + id);
  }

  const statusBadge = (s) =>
    s === 'TRANSFERRED' ? 'ok' : s === 'FAILED' ? 'bad'
      : s === 'RE_REPORTED' ? 'gray' : s === 'ISSUED' ? 'info' : 'warn';

  $: r = detail?.replacement;
</script>

<div class="panel">
  <h2>卡片与持卡人核验</h2>
  <div class="row">
    {#each cards as card}
      <button class="pill-card" class:active={fCard === card.id} on:click={() => (fCard = card.id)}>
        <span class="mono">{card.id}</span>
        <span>{card.holderName}</span>
        <span class="badge {card.status === 'ACTIVE' ? 'ok' : card.status === 'STOLEN' ? 'warn' : 'gray'}">
          {card.status === 'ACTIVE' ? '正常' : card.status === 'STOLEN' ? '停用' : card.status === 'REPLACED' ? '已转出' : card.status}
          ¥{fmtAmt(card.balance)}
        </span>
      </button>
    {/each}
    <button class="btn ghost" on:click={refreshAll}>刷新</button>
  </div>
  <div class="row">
    <label>证件号</label>
    <input value={fId} on:input={(e) => (fId = e.target.value)} style="width:220px" />
    <button class="btn warn" on:click={report}>核验身份并挂失停用</button>
    <button class="btn danger" on:click={reReport}>对所选卡再次挂失（新卡丢卡场景）</button>
    <span class="muted small">演示证件号 110101199001011234；错误证件号会返回 403</span>
  </div>
</div>

<div class="grid2">
  <div class="panel">
    <h2>补卡单列表</h2>
    <table>
      <thead><tr><th>补卡单号</th><th>旧卡</th><th>新卡</th><th>状态</th><th class="num">可转/已转</th></tr></thead>
      <tbody>
        {#each cases as cs}
          <tr style="cursor:pointer; background:{selected === cs.id ? 'var(--info-bg)' : ''}"
              on:click={() => selectCase(cs.id)}>
            <td class="mono">{cs.id}</td>
            <td class="mono">{cs.oldCardId}</td>
            <td class="mono">{cs.newCardId || '—'}</td>
            <td><span class="badge {statusBadge(cs.status)}">{cs.status}</span></td>
            <td class="num">¥{fmtAmt(cs.transferableAmount)} / ¥{fmtAmt(cs.transferredAmount)}</td>
          </tr>
        {:else}
          <tr><td colspan="5" class="muted">暂无补卡单</td></tr>
        {/each}
      </tbody>
    </table>
  </div>

  {#if detail && r}
    <div class="panel">
      <h2>工单操作 · {r.id}</h2>
      <div class="row">
        <label><input type="checkbox" bind:checked={fFail} /> 模拟制卡中心故障</label>
        <button class="btn ok" on:click={issue}>
          {r.status === 'FAILED' ? '重新制卡（失败单据保留中）' : '制出新卡'}
        </button>
      </div>
      {#if r.failReason}
        <p class="badge bad" style="margin-top:8px">上次失败原因（第 {r.attemptCount} 次）：{r.failReason}</p>
      {/if}
      <div class="row" style="margin-top:10px">
        <input placeholder="转移回执号（可留空自动生成）" value={fReceipt}
               on:input={(e) => (fReceipt = e.target.value)} style="width:260px" />
        <button class="btn" on:click={transfer} disabled={r.status === 'TRANSFERRED'}>
          {r.status === 'TRANSFERRED' ? '已转移（重复请求幂等）' : '清算完成后手动转移'}
        </button>
        <button class="btn ghost" on:click={onGoSim}>去模拟器处理在途消费 →</button>
      </div>
      <div class="formula" style="margin-top:14px">
        <div><b>挂失快照</b> ¥{fmtAmt(r.reportedBalance)}（{fmtTime(r.reportedAt)}）</div>
        <div><b>在途已清算</b> −¥{fmtAmt(r.pendingSettledTotal)}　<b>晚到消费</b> −¥{fmtAmt(r.lateTotal)}</div>
        <div><b>可转余额</b> ¥{fmtAmt(r.transferableAmount)}　<b>已转移</b> ¥{fmtAmt(r.transferredAmount)}
          {#if r.transferReceiptId}　回执 <code class="receipt">{r.transferReceiptId}</code>{/if}</div>
        <div class="muted small" style="margin-top:6px">
          旧卡转出金额必须等于新卡转入金额；旧卡 oldCardId 唯一约束保证一张旧卡只有一张有效新卡。
        </div>
      </div>
    </div>
  {/if}
</div>

{#if detail && r}
  <div class="grid2">
    <div class="panel">
      <h2><span class="tag-old">旧卡 {r.oldCardId}</span>
        <span class="badge gray">{detail.oldCard.statusZh} · 余额 ¥{fmtAmt(detail.oldCard.balance)}</span>
      </h2>
      <TxnTable txs={detail.oldTransactions} masked={false} />
    </div>
    <div class="panel">
      <h2><span class="tag-new">新卡</span>
        {#if detail.newCard}
          <span class="badge ok">{detail.newCard.id} · {detail.newCard.statusZh} · 余额 ¥{fmtAmt(detail.newCard.balance)}</span>
        {:else}<span class="muted">尚未制出</span>{/if}
      </h2>
      {#if detail.newCard}<TxnTable txs={detail.newTransactions} masked={false} />{/if}
    </div>
  </div>

  <div class="panel">
    <h2>逐笔处理依据时间线（每笔扣款、每张回执都可追溯）</h2>
    <Timeline events={detail.events} />
  </div>

  {#if detail.differences.length > 0}
    <div class="panel">
      <h2>晚到差额单</h2>
      <table>
        <thead><tr><th>差额单</th><th>卡</th><th>原始流水</th><th class="num">金额</th><th>阶段</th><th>是否解决</th><th>说明</th></tr></thead>
        <tbody>
          {#each detail.differences as d}
            <tr>
              <td class="mono">{d.id}</td><td class="mono">{d.cardId}</td><td class="mono">{d.txnId}</td>
              <td class="num amt-out">−{fmtAmt(d.amount)}</td>
              <td>{d.stage}</td>
              <td><span class="badge {d.resolved ? 'ok' : 'warn'}">{d.resolved ? '已解决' : '待追账'}</span></td>
              <td class="muted small">{d.resolution || '余额已转走，沿卡链待追账'}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
{/if}
