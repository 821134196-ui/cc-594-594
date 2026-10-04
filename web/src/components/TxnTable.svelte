<script>
  import { fmtTime, fmtAmt } from '../lib/api.js';

  export let txs = [];
  export let masked = true;

  const typeBadge = (t) =>
    t === 'TRANSFER_OUT' ? 'bad' : t === 'TRANSFER_IN' || t === 'TOPUP' ? 'ok'
      : t === 'LATE_RECOVERY' ? 'warn' : 'gray';

  const statusBadge = (s) =>
    s === 'POSTED' ? 'ok' : s === 'PENDING' ? 'warn' : 'bad';

  const amtClass = (t) =>
    t === 'TRANSFER_IN' || t === 'TOPUP' ? 'amt-in'
      : t === 'TRANSFER_OUT' || t === 'FARE' || t === 'LATE_RECOVERY' ? 'amt-out' : '';

  const sign = (t) =>
    t === 'TRANSFER_IN' || t === 'TOPUP' ? '+' : '−';
</script>

<table>
  <thead>
    <tr>
      <th>乘车/发生时间</th>
      <th>流水号</th>
      <th>类型</th>
      <th class="num">金额(元)</th>
      <th class="num">变动后余额</th>
      <th>状态</th>
      <th>备注 / 回执</th>
    </tr>
  </thead>
  <tbody>
    {#each txs as tx}
      <tr>
        <td class="mono muted">{fmtTime(tx.occurredAt)}</td>
        <td class="mono">{masked ? tx.id.slice(0, 6) + '…' : tx.id}</td>
        <td>
          <span class="badge {typeBadge(tx.type)}">{tx.typeZh || tx.type}</span>
          {#if tx.late}<span class="badge warn" title="挂失后才清算的挂失前消费">晚到</span>{/if}
        </td>
        <td class="num {amtClass(tx.type)}">{sign(tx.type)}{fmtAmt(tx.amount)}</td>
        <td class="num">{tx.balanceAfter == null ? '—' : fmtAmt(tx.balanceAfter)}</td>
        <td><span class="badge {statusBadge(tx.status)}">{tx.statusZh || tx.status}</span></td>
        <td class="muted small">
          {tx.note || ''}
          {#if tx.receiptId}<br /><code class="receipt">回执 {tx.receiptId}</code>{/if}
        </td>
      </tr>
    {:else}
      <tr><td colspan="7" class="muted">暂无流水</td></tr>
    {/each}
  </tbody>
</table>
