<script>
  import PassengerPage from './pages/PassengerPage.svelte';
  import AgentPage from './pages/AgentPage.svelte';
  import SimPage from './pages/SimPage.svelte';

  let tab = 'passenger';
  const tabs = [
    { id: 'passenger', label: '乘客 · 办理进度' },
    { id: 'agent', label: '客服 · 补卡办理' },
    { id: 'sim', label: '模拟器 · 闸机/清算' }
  ];

  import { toast } from './lib/store.js';
</script>

<header class="topbar">
  <h1>公交卡挂失补卡与余额转移系统</h1>
  <p>核身停用旧卡 → 等待在途清算 → 可转余额一次性转入新卡（转出 = 转入，全程留痕可核对）</p>
</header>

<nav class="tabs">
  {#each tabs as t}
    <button class={tab === t.id ? 'active' : ''} on:click={() => (tab = t.id)}>{t.label}</button>
  {/each}
</nav>

<main>
  {#if tab === 'passenger'}
    <PassengerPage />
  {:else if tab === 'agent'}
    <AgentPage onGoSim={() => (tab = 'sim')} />
  {:else}
    <SimPage />
  {/if}
</main>

{#if $toast}
  <div class="toast {$toast.kind === 'error' ? 'err' : ''}">{$toast.text}</div>
{/if}
