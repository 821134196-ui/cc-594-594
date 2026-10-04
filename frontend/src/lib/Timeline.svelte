<script>
  import { fmtTime } from '../api.js';

  let { events = [] } = $props();

  const danger = new Set(['GATE_REJECTED', 'RECEIPT_DUPLICATE', 'TRANSFER_FAILED', 'ISSUE_FAILED', 'CLAWBACK_WAITING']);
  const ok = new Set(['TRANSFER_DONE', 'CLAWBACK_DONE', 'NEW_CARD_ISSUED', 'CLEARING_COMPLETE', 'OLD_CARD_TERMINATED', 'CASE_REOPENED']);
  const warn = new Set(['WAITING_CLEARING', 'LATE_FARE_DISCOVERED', 'READY_TO_TRANSFER']);

  function cls(code) {
    if (danger.has(code)) return 'danger';
    if (ok.has(code)) return 'ok';
    if (warn.has(code)) return 'warn';
    return '';
  }
</script>

<ul class="timeline">
  {#each events as e, i (i)}
    <li class={cls(e.code)}>
      <div class="t">{fmtTime(e.at)}</div>
      <div class="code">{e.code}{e.refType ? ' · ' + e.refType + (e.refId != null ? ' #' + e.refId : '') : ''}</div>
      <div class="d">{e.detail}</div>
    </li>
  {/each}
</ul>
