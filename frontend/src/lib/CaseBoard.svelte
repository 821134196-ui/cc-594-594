<script>
  import TxnTable from './TxnTable.svelte';
  import Timeline from './Timeline.svelte';
  import { yuan, fmtTime } from '../api.js';

  let { detail: d, mode = 'passenger' } = $props();

  const phaseMap = {
    OLD_CARD_BLOCKED: { cls: 'info', icon: '🛑', title: '旧卡已停用，等待制发新卡' },
    WAITING_CLEARING: { cls: 'wait', icon: '⏳', title: '余额仍在等待清算，暂未转入新卡' },
    READY_TO_TRANSFER: { cls: 'wait', icon: '🧮', title: '在途消费已清算完毕，可转余额已确定' },
    TRANSFERRED: { cls: 'done', icon: '✅', title: '余额已转入新卡，可正常乘车' },
    FAILED: { cls: 'fail', icon: '⚠️', title: '办理失败，处理记录已保留，未完成的转移未丢失' }
  };
  const ph = phaseMap[d.status] || { cls: 'info', icon: '📋', title: d.statusLabel };

  function cardClass(c, side) {
    if (!c) return '';
    if (side === 'old') return c.status === 'REPLACED' ? 'lost' : 'lost';
    return 'new';
  }
</script>

<div class="phase-banner {ph.cls}">
  <div class="phase-icon">{ph.icon}</div>
  <div>
    <div class="big">{ph.title}</div>
    <div>{d.moneyPhase}</div>
    {#if d.failureReason}<div style="margin-top:4px">失败原因：{d.failureReason}</div>{/if}
  </div>
</div>

<div class="dual">
  <!-- 旧卡 -->
  <div>
    <div class="bank-card {cardClass(d.oldCard, 'old')}">
      <div class="row">
        <div>
          <div class="muted" style="color:rgba(255,255,255,.85)">旧卡（挂失停用）</div>
          <div class="cn">{d.oldCard.cardNoMasked}</div>
          <div style="font-size:12px;opacity:.9">{d.oldCard.holderName}</div>
        </div>
        <span class="st">{d.oldCard.statusLabel}</span>
      </div>
      <div class="row" style="margin-top:8px">
        <div>
          <div style="font-size:11px;opacity:.8">已清算余额（转移后为 0）</div>
          <div class="bal">¥ {d.oldCard.balanceText}</div>
          <div style="font-size:10.5px;opacity:.75">勾稽：已清算流水合计 ¥ {yuan(d.oldCard.settledLedgerSumCents)}{d.oldCard.settledLedgerSumCents === d.oldCard.balanceCents ? ' ✔' : ' ✘'}</div>
        </div>
        {#if d.oldCard.pendingFareCount > 0}
          <div style="text-align:right;font-size:12px">
            在途 {d.oldCard.pendingFareCount} 笔<br/>
            预留 ¥ {yuan(d.oldCard.pendingFareCents)}
          </div>
        {/if}
      </div>
    </div>
    <TxnTable txns={d.oldTxns} title="旧卡脱敏流水" empty="旧卡暂无流水" />
  </div>

  <!-- 新卡 -->
  <div>
    {#if d.newCard}
      <div class="bank-card {cardClass(d.newCard, 'new')}">
        <div class="row">
          <div>
            <div class="muted" style="color:rgba(255,255,255,.85)">新卡（补办）</div>
            <div class="cn">{d.newCard.cardNoMasked}</div>
            <div style="font-size:12px;opacity:.9">{d.newCard.holderName}</div>
          </div>
          <span class="st">{d.newCard.statusLabel}</span>
        </div>
        <div style="margin-top:8px">
          <div style="font-size:11px;opacity:.8">当前可用余额</div>
          <div class="bal">¥ {d.newCard.balanceText}</div>
          <div style="font-size:10.5px;opacity:.75">勾稽：已清算流水合计 ¥ {yuan(d.newCard.settledLedgerSumCents)}{d.newCard.settledLedgerSumCents === d.newCard.balanceCents ? ' ✔' : ' ✘'}</div>
        </div>
      </div>
      <TxnTable txns={d.newTxns} title="新卡脱敏流水" empty="新卡暂无流水（制卡后初始余额 0.00，等待转入）" />
    {:else}
      <div class="bank-card lost">
        <div class="muted" style="color:rgba(255,255,255,.85)">新卡（补办）</div>
        <div style="margin:14px 0;font-size:13px;opacity:.92">制卡服务尚未发放入新卡</div>
      </div>
      <div class="card-panel"><div class="muted">完成身份核验与旧卡停用后，由客服制发新卡。</div></div>
    {/if}
  </div>
</div>

<!-- 转移对账 -->
<div class="card-panel">
  <h3>💰 余额转移状态与对账依据</h3>
  <div class="kv">
    <dt>受理时旧卡快照</dt>
    <dd>¥ {yuan(d.balanceAtReportCents)}（{fmtTime(d.createdAt)} 受理）</dd>
    <dt>受理时已登记在途</dt>
    <dd>¥ {yuan(d.pendingAtReportCents)}（不实际扣款，清算后决定）</dd>
    <dt>旧卡累计转出</dt>
    <dd>¥ {yuan(d.transferredOutCents)}</dd>
    <dt>新卡累计转入</dt>
    <dd>¥ {yuan(d.transferredInCents)}</dd>
    <dt>金额一致性</dt>
    <dd class={d.transferBalanced ? 'check-ok' : 'check-bad'}>
      {d.transferBalanced ? '✔ 转出 = 转入，严格一致' : '✘ 转出/转入不一致（异常）'}
    </dd>
    {#if d.lateClawbackCents > 0}
      <dt>晚到消费已追回</dt>
      <dd>¥ {yuan(d.lateClawbackCents)}（原旧卡流水保留，逐笔挂账追扣）</dd>
    {/if}
  </div>

  {#if d.transfers.length > 0}
    <div class="section-title" style="margin-top:12px">转移尝试记录（失败也留痕、可重试）</div>
    <div class="pill-row">
      {#each d.transfers as t (t.attemptNo)}
        <div class="adj-row" style="flex:1;min-width:260px;margin:0">
          <div style="display:flex;justify-content:space-between;align-items:center">
            <strong>第 {t.attemptNo} 次尝试</strong>
            <span class="badge {t.status}">{t.status === 'COMPLETED' ? '成功' : '失败'}</span>
          </div>
          <div class="mono" style="margin:4px 0">金额 ¥ {t.amountText} · {fmtTime(t.attemptedAt)}</div>
          {#if t.outTxnId}
            <div class="muted">转出流水 #{t.outTxnId} ↔ 转入流水 #{t.inTxnId}</div>
          {/if}
          <div class="note-line">{t.note}</div>
        </div>
      {/each}
    </div>
  {/if}

  {#if d.adjustments.length > 0}
    <div class="section-title" style="margin-top:14px">晚到交易差额（不修改旧交易，逐笔挂账）</div>
    {#each d.adjustments as a (a.id)}
      <div class="adj-row">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <strong>差额 #{a.id} · ¥ {a.amountText}</strong>
          <span class="badge {a.status}">{a.status === 'RESOLVED' ? '已追回' : '待追扣（记录保留）'}</span>
        </div>
        <div class="muted mono" style="margin-top:3px">
          原 FARE 流水 #{a.originalFareTxnId} · 旧卡挂平 LATE_OFFSET #{a.offsetTxnId}
          {#if a.clawbackTxnId} · 新卡追扣 LATE_CLAWBACK #{a.clawbackTxnId}{/if}
        </div>
        <div class="note-line">{a.note}</div>
      </div>
    {/each}
  {/if}

  {#if d.receipts.length > 0}
    <div class="section-title" style="margin-top:14px">清算回执（重复回执只登记、不多转钱）</div>
    {#each d.receipts as r (r.receiptNo)}
      <div class="adj-row" style="display:flex;justify-content:space-between;gap:10px;align-items:center">
        <div>
          <strong class="mono">{r.receiptNo}</strong>
          <span class="badge {r.status}" style="margin-left:8px">{r.status === 'ACCEPTED' ? '首次受理' : '重复·已忽略'}</span>
          <div class="muted" style="margin-top:2px">{r.note}</div>
        </div>
        <div class="mono muted" style="white-space:nowrap">{fmtTime(r.receivedAt)}</div>
      </div>
    {/each}
  {/if}
</div>

<!-- 进度时间轴 -->
<div class="card-panel">
  <h3>📋 办理进度（乘客可查全程）</h3>
  <Timeline events={d.events} />
</div>
