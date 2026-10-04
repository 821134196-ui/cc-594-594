<script>
  import { api, fmtAmt } from '../lib/api.js';
  import { showToast } from '../lib/store.js';
  import TxnTable from '../components/TxnTable.svelte';
  import Timeline from '../components/Timeline.svelte';

  let caseId = localStorage.getItem('buscard.caseId') || '';
  let c = null;
  let loading = false;

  async function load() {
    if (!caseId.trim()) {
      showToast('请输入补卡单号', 'error');
      return;
    }
    loading = true;
    try {
      c = await api.get('/passenger/cases/' + encodeURIComponent(caseId.trim()));
      localStorage.setItem('buscard.caseId', c.id);
    } catch (e) {
      c = null;
      showToast(e.message, 'error');
    } finally {
      loading = false;
    }
  }

  $: bannerClass = c
    ? c.phase === 'DONE' ? 'done'
      : c.phase === 'ISSUE_FAILED' ? 'failed'
        : c.phase === 'WAITING_CLEARING' ? 'waiting' : 'reported'
    : '';

  // 进度轮询：等待清算/转移中时每 3 秒刷新
  let timer;
  $: if (c && (c.phase === 'WAITING_CLEARING' || c.phase === 'READY' || c.phase === 'REPORTED')) {
    clearInterval(timer);
    timer = setInterval(load, 3000);
  } else {
    clearInterval(timer);
  }
</script>

<div class="panel">
  <h2>查询我的补卡进度</h2>
  <div class="row">
    <input placeholder="补卡单号，如 RPL-XXXXXXXXXX" value={caseId}
           on:input={(e) => (caseId = e.target.value)} style="width:300px"
           on:keydown={(e) => e.key === 'Enter' && load()} />
    <button class="btn" on:click={load} disabled={loading}>查询进度</button>
    <span class="muted small">单号由客服在挂失受理时提供；页面只显示脱敏卡号与流水</span>
  </div>
</div>

{#if c}
  <div class="banner {bannerClass}">
    <strong>当前状态：{c.statusZh}</strong>
    <div>{c.progressText}</div>
  </div>

  <div class="panel">
    <h2>余额转移明细 <span class="muted small">（金额单位：元）</span></h2>
    <div class="stat">
      <div class="item"><div class="k">挂失瞬间旧卡余额</div><div class="v">¥{fmtAmt(c.reportedBalance)}</div></div>
      <div class="item"><div class="k">减 · 在途消费清算</div><div class="v" style="color:var(--warn)">−¥{fmtAmt(c.pendingSettledTotal)}</div></div>
      <div class="item"><div class="k">减 · 晚到消费</div><div class="v" style="color:var(--warn)">−¥{fmtAmt(c.lateTotal)}</div></div>
      <div class="item">
        <div class="k">{c.status === 'TRANSFERRED' ? '已转入新卡' : '当前可转余额（等待清算中）'}</div>
        <div class="v" style="color:var(--brand)">
          ¥{fmtAmt(c.status === 'TRANSFERRED' ? c.transferredAmount : c.transferableAmount)}
        </div>
      </div>
    </div>
    <div class="formula">
      计算依据：<b>可转余额 = 挂失余额 {fmtAmt(c.reportedBalance)} − 在途清算 {fmtAmt(c.pendingSettledTotal)}
      − 晚到消费 {fmtAmt(c.lateTotal)}</b>
      {#if c.status === 'TRANSFERRED'}
        ；已于转移回执 <code class="receipt">{c.transferReceiptId}</code> 一次性转入新卡，
        旧卡转出 ¥{fmtAmt(c.transferredAmount)} ＝ 新卡转入 ¥{fmtAmt(c.transferredAmount)}。
      {:else}
        ；余额仍在等待在途消费清算，清算完成后自动转入新卡。
      {/if}
    </div>
  </div>

  <div class="grid2">
    <div class="panel">
      <h2><span class="tag-old">旧卡</span> {c.oldCard?.id}
        <span class="badge gray">{c.oldCard?.statusZh}</span>
      </h2>
      <div class="muted small" style="margin-bottom:8px">
        当前账面余额 ¥{fmtAmt(c.oldCard?.balance)}（挂失后停用，仅接受清算入账）
      </div>
      <TxnTable txs={c.oldTransactions} masked={true} />
    </div>
    <div class="panel">
      <h2><span class="tag-new">新卡</span>
        {c.newCard ? c.newCard.id : '尚未制出'}
        {#if c.newCard}<span class="badge ok">{c.newCard.statusZh}</span>{/if}
      </h2>
      {#if c.newCard}
        <div class="muted small" style="margin-bottom:8px">
          当前余额 ¥{fmtAmt(c.newCard.balance)}
        </div>
        <TxnTable txs={c.newTransactions} masked={true} />
      {:else}
        <p class="muted">客服制卡成功后，新卡与转移后的余额会显示在这里。</p>
      {/if}
    </div>
  </div>

  <div class="panel">
    <h2>办理进度时间线</h2>
    <Timeline events={c.events} />
  </div>

  {#if c.differences.length > 0}
    <div class="panel">
      <h2>晚到消费差额登记（原始乘车流水保留，未修改旧交易）</h2>
      <table>
        <thead><tr><th>差额单号</th><th>原始流水</th><th class="num">金额</th><th>阶段</th><th>处理说明</th></tr></thead>
        <tbody>
          {#each c.differences as d}
            <tr>
              <td class="mono">{d.id}</td>
              <td class="mono">{d.txnId}</td>
              <td class="num amt-out">−{fmtAmt(d.amount)}</td>
              <td><span class="badge {d.resolved ? 'ok' : 'warn'}">{d.stage === 'BEFORE_TRANSFER' ? '转移前冲减' : d.stage === 'AFTER_TRANSFER' ? '转移后待追账' : '已追收'}</span></td>
              <td class="muted small">{d.resolution || '保留待处理'}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
{/if}
