<script>
  import { onMount } from 'svelte';
  import { api } from './api.js';
  import CaseBoard from './lib/CaseBoard.svelte';
  import AgentPanel from './lib/AgentPanel.svelte';
  import DemoPanel from './lib/DemoPanel.svelte';

  let tab = $state('passenger'); // passenger | agent | demo
  let cases = $state([]);
  let selectedNo = $state(null);
  let detail = $state(null);
  let loading = $state(false);
  let toast = $state(null);

  function showToast(msg, kind = 'info') {
    toast = { msg, kind, t: Date.now() };
    setTimeout(() => { if (toast && Date.now() - toast.t >= 2400) toast = null; }, 2600);
  }

  async function loadCases() {
    cases = await api.listCases();
    if (!selectedNo && cases.length) {
      // 默认选中张伟的进行中案件（若有），否则第一个
      const prefer = cases.find((c) => c.holderName === '张伟') || cases[0];
      selectedNo = prefer.caseNo;
    }
  }

  async function loadDetail() {
    if (!selectedNo) { detail = null; return; }
    loading = true;
    try { detail = await api.caseDetail(selectedNo); }
    finally { loading = false; }
  }

  async function refresh() {
    await loadCases();
    await loadDetail();
  }

  onMount(refresh);

  $effect(() => { selectedNo; loadDetail(); });

  function selectCase(no) { selectedNo = no; }
</script>

<header class="topbar">
  <div>
    <h1>🚌 公交卡挂失补卡 · 余额转移系统</h1>
    <div class="sub">旧卡停用 → 在途清算 → 可转余额核对 → 等额转入新卡（Java / Quarkus / Hibernate / SQLite / Svelte）</div>
  </div>
  <div class="tabs">
    <button class={tab === 'passenger' ? 'active' : ''} onclick={() => (tab = 'passenger')}>乘客进度</button>
    <button class={tab === 'agent' ? 'active' : ''} onclick={() => (tab = 'agent')}>客服办理</button>
    <button class={tab === 'demo' ? 'active' : ''} onclick={() => (tab = 'demo')}>场景演示台</button>
  </div>
</header>

<div class="layout">
  <aside class="sidebar">
    <h2>补卡案件</h2>
    {#each cases as c (c.caseNo)}
      <div
        class="case-item"
        class:active={selectedNo === c.caseNo}
        onclick={() => selectCase(c.caseNo)}
        role="button"
      >
        <div class="no">{c.caseNo}</div>
        <div class="holder">{c.holderName}</div>
        <div class="cards">旧 {c.oldCardNo}{c.newCardNo ? ' → 新 ' + c.newCardNo : ''}</div>
        <div style="margin-top:5px"><span class="badge {c.status}">{c.statusLabel}</span></div>
      </div>
    {/each}
    <button class="btn ghost" style="width:100%;margin-top:8px" onclick={refresh}>刷新列表</button>
  </aside>

  <main>
    {#if loading && !detail}
      <div class="muted">加载中…</div>
    {:else if !detail}
      <div class="muted">请选择一个案件</div>
    {:else if tab === 'passenger'}
      <CaseBoard {detail} mode="passenger" />
    {:else if tab === 'agent'}
      <AgentPanel {detail} {refresh} {showToast} {selectCase} />
    {:else}
      <DemoPanel {detail} {refresh} {showToast} />
    {/if}
  </main>
</div>

{#if toast}
  <div class="toast {toast.kind === 'error' ? 'err' : toast.kind === 'ok' ? 'ok' : ''}">
    {toast.msg}
  </div>
{/if}
