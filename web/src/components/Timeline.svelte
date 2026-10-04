<script>
  import { fmtTime } from '../lib/api.js';
  export let events = [];
</script>

<ul class="timeline">
  {#each events as e}
    <li class="kind-{e.type}">
      <div class="t-time">{fmtTime(e.at)}</div>
      <div class="t-msg">
        {e.message}
        {#if e.refId}<code class="receipt">{e.refId}</code>{/if}
      </div>
    </li>
  {:else}
    <li class="muted">暂无事件</li>
  {/each}
</ul>
