<script>
  import { yuan, fmtTime } from '../api.js';

  let { txns = [], title = '', empty = '暂无流水' } = $props();

  const kindClass = {
    LATE_CLAWBACK: 'LATE_CLAWBACK',
    LATE_OFFSET: 'LATE_OFFSET',
    TRANSFER_IN: 'TRANSFER_IN',
    TRANSFER_OUT: 'TRANSFER_OUT'
  };
</script>

<div class="card-panel">
  <h3>{title} <span class="muted">（{txns.length} 笔，卡号/证件均已脱敏）</span></h3>
  {#if txns.length === 0}
    <div class="muted">{empty}</div>
  {:else}
    <div style="overflow-x:auto">
      <table class="txn">
        <thead>
          <tr>
            <th>#</th><th>类型</th><th>金额</th><th>状态</th><th>发生时间</th>
            <th>渠道/设备</th><th>清算回执</th><th>说明 / 配对</th>
          </tr>
        </thead>
        <tbody>
          {#each txns as t (t.id)}
            <tr>
              <td class="mono">{t.id}</td>
              <td><span class="tag kind {kindClass[t.kind] || ''}">{t.kindLabel}</span></td>
              <td class={t.signedAmountCents >= 0 ? 'amt-pos' : 'amt-neg'}>{t.amountText}</td>
              <td><span class="tag {t.status}">{t.status === 'PENDING' ? '在途·未扣款' : '已清算'}</span></td>
              <td class="mono muted" style="white-space:nowrap">{fmtTime(t.occurredAt)}</td>
              <td class="muted" style="white-space:nowrap">
                {t.channel}{t.deviceId ? ' / ' + t.deviceId : ''}
              </td>
              <td class="mono muted">{t.receiptNo || ''}</td>
              <td style="min-width:180px">
                {t.note || ''}
                {#if t.linkedTxnId}<div class="muted">↔ 配对流水 #{t.linkedTxnId}</div>{/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
</div>
