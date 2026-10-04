<script>
  import { api } from '../api.js';

  let { detail: d, refresh, showToast } = $props();

  let swipeCard = $state('');
  let swipeFare = $state(200);
  let swipeResult = $state(null);

  let receiptNo = $state('RCPT-MANUAL-1');
  let lineEventKey = $state('E-MANUAL-1');
  let lineAmount = $state(200);
  let lineOccurredAt = $state('');
  let receiptResult = $state(null);

  let rechargeCard = $state('');
  let rechargeAmount = $state(2000);

  $effect(() => {
    if (d?.oldCard?.cardNo && !swipeCard) swipeCard = d.oldCard.cardNo;
    if (d?.newCard?.cardNo && !rechargeCard) rechargeCard = d.newCard.cardNo;
  });

  async function call(fn, okMsg) {
    try {
      const r = await fn();
      if (r && r.ok === false) showToast('失败但已留痕：' + r.error, 'error');
      else showToast(okMsg, 'ok');
      await refresh();
      return r;
    } catch (e) {
      showToast(e.message, 'error');
      await refresh();
      return null;
    }
  }

  async function doSwipe() {
    try {
      swipeResult = await api.swipe({
        cardNo: swipeCard.trim(), fareCents: Number(swipeFare),
        deviceId: 'GATE-DEMO', eventKey: 'SW-DEMO-' + Date.now()
      });
      showToast(swipeResult.allowed ? '闸机放行（在途消费已登记）' : '闸机拒绝通行',
        swipeResult.allowed ? 'ok' : 'error');
      await refresh();
    } catch (e) { showToast(e.message, 'error'); }
  }

  async function submitReceipt(caseNo, no, lines) {
    return api.receipt({ caseNo, receiptNo: no, lines });
  }

  async function doReceipt() {
    try {
      const lines = [{
        eventKey: lineEventKey.trim(),
        amountCents: Number(lineAmount),
        deviceId: 'OFFLINE-DEMO',
        ...(lineOccurredAt ? { occurredAt: new Date(lineOccurredAt).toISOString() } : {})
      }];
      receiptResult = await submitReceipt(d.caseNo, receiptNo.trim(), lines);
      showToast(receiptResult.status === 'DUPLICATE'
        ? '重复回执：已忽略，未多扣一分钱'
        : `回执受理，结算 ${receiptResult.settledLineCount} 笔`,
        receiptResult.status === 'DUPLICATE' ? 'error' : 'ok');
      await refresh();
    } catch (e) { showToast(e.message, 'error'); }
  }

  // ---------- 一键引导剧本 ----------
  async function guideReject() {
    swipeCard = d.oldCard.cardNo;
    await doSwipe();
  }

  async function guideDuplicate() {
    const pending = d.oldTxns.find((t) => t.status === 'PENDING' && t.eventKey);
    if (!pending) {
      showToast('当前案件没有在途消费；请重置后选择张伟案件（自带一笔在途 E-A-P1），或先在挂失前刷一笔再挂失', 'error');
      return;
    }
    const no = 'RCPT-GUIDE-DUP-' + d.caseNo;
    const body = {
      caseNo: d.caseNo, receiptNo: no,
      lines: [{ eventKey: pending.eventKey, amountCents: Math.abs(pending.signedAmountCents), deviceId: pending.deviceId }]
    };
    receiptNo = no; lineEventKey = pending.eventKey; lineAmount = Math.abs(pending.signedAmountCents);
    await call(() => submitReceipt(body.caseNo, body.receiptNo, body.lines), '首次回执已受理');
    const again = await call(() => submitReceipt(body.caseNo, body.receiptNo, body.lines), '重复回执已被幂等忽略（未多扣多转）');
    receiptResult = again;
  }

  async function guideLateBeforeTransfer() {
    if (d.status !== 'WAITING_CLEARING' && d.status !== 'OLD_CARD_BLOCKED') {
      showToast('此剧本需要案件处于“旧卡停用/等待清算”阶段（重置后选张伟，先在客服页挂失+制卡）', 'error');
      return;
    }
    const no = 'RCPT-GUIDE-LATE0-' + d.caseNo;
    const key = 'E-GUIDE-LATE0-' + d.caseNo;
    receiptNo = no; lineEventKey = key; lineAmount = 300;
    const r = await call(() => submitReceipt(d.caseNo, no, [{
      eventKey: key, amountCents: 300, deviceId: 'OFFLINE-DEMO',
      occurredAt: new Date(Date.now() - 86400000).toISOString()
    }]), '晚到消费已清算（转账前）：可转余额减少 3.00 元');
    receiptResult = r;
  }

  async function guideLateAfterTransfer() {
    if (d.status !== 'TRANSFERRED') {
      showToast('此剧本需要案件已完成转账（先在客服页执行余额转移）', 'error');
      return;
    }
    const no = 'RCPT-GUIDE-LATE1-' + d.caseNo;
    const key = 'E-GUIDE-LATE1-' + d.caseNo;
    receiptNo = no; lineEventKey = key; lineAmount = 500;
    const r = await call(() => submitReceipt(d.caseNo, no, [{
      eventKey: key, amountCents: 500, deviceId: 'OFFLINE-DEMO',
      occurredAt: new Date(Date.now() - 2 * 86400000).toISOString()
    }]), '晚到交易到达：原流水保留，已从新卡追扣 5.00 元');
    receiptResult = r;
  }

  const guideArmTransferFail = () => call(() => api.armTransferFailure(), '已武装：下一次转账将失败并留痕');
  const guideArmIssueFail = () => call(() => api.armCardFactoryFailure(), '已武装：下一次制卡将失败并留痕');
  const doRecharge = () => call(() => api.recharge(rechargeCard.trim(), Number(rechargeAmount)), '充值成功，可再试追扣');
  async function doReset() {
    if (!confirm('确定清空并重置为种子数据？')) return;
    await call(() => api.reset(), '已重置为初始演示数据');
  }
</script>

<div class="card-panel">
  <h3>🎬 场景演示台 · 案件 {d.caseNo}（{d.oldCard.holderName}）</h3>
  <div class="muted" style="margin-bottom:10px">
    推荐路径：重置 → 选「张伟」案件 → 客服页 挂失+制卡 → 回本页依次点 ①②③ → 客服页转账 → 回本页点 ④。
  </div>
  <div class="pill-row">
    <button class="btn danger" onclick={guideReject}>① 旧卡刷卡 → 应被拒绝</button>
    <button class="btn ghost" onclick={guideDuplicate}>② 重复清算回执 → 不多转钱</button>
    <button class="btn ghost" onclick={guideLateBeforeTransfer}>③ 晚到交易（转账前）→ 可转余额减少</button>
    <button class="btn ghost" onclick={guideLateAfterTransfer}>④ 晚到交易（转账后）→ 原流水保留+新卡追扣</button>
    <button class="btn ghost" onclick={guideArmIssueFail}>💥 武装：下次制卡失败</button>
    <button class="btn ghost" onclick={guideArmTransferFail}>💥 武装：下次转账失败</button>
    <button class="btn" onclick={doReset}>↺ 重置全部演示数据</button>
  </div>
</div>

<div class="dual">
  <div class="card-panel">
    <h3>🚉 闸机模拟（刷卡）</h3>
    <label>卡号（默认当前旧卡）</label>
    <input bind:value={swipeCard} />
    <label>票价（分）</label>
    <input type="number" bind:value={swipeFare} />
    <div style="margin-top:10px"><button class="btn" onclick={doSwipe}>刷卡</button></div>
    {#if swipeResult}
      <div class="adj-row" style="margin-top:10px">
        <strong class={swipeResult.allowed ? 'check-ok' : 'check-bad'}>
          {swipeResult.allowed ? '✔ 放行' : '✘ 拒绝'}
        </strong>
        <div class="note-line">{swipeResult.message}</div>
        <div class="muted mono" style="margin-top:4px">{swipeResult.cardNo} · {swipeResult.timestamp}</div>
      </div>
    {/if}
    <div class="muted" style="margin-top:8px">
      ACTIVE 卡：放行并登记一笔在途消费；LOST / REPLACED 旧卡：一律拒绝，拒绝事件写入案件进度。
    </div>
  </div>

  <div class="card-panel">
    <h3>🏦 清算中心模拟（回执上报）</h3>
    <label>回执号 receiptNo（相同号重放即重复回执）</label>
    <input bind:value={receiptNo} />
    <label>行事件号 eventKey</label>
    <input bind:value={lineEventKey} />
    <label>金额（分）</label>
    <input type="number" bind:value={lineAmount} />
    <label>乘车发生时间（留空=现在；选更早时间模拟离线晚到）</label>
    <input type="datetime-local" bind:value={lineOccurredAt} />
    <div style="margin-top:10px"><button class="btn" onclick={doReceipt}>提交回执</button></div>
    {#if receiptResult}
      <div class="adj-row" style="margin-top:10px">
        <strong>回执结果：<span class="badge {receiptResult.status}">{receiptResult.status}</span></strong>
        <div class="muted mono" style="margin:4px 0">
          首次结算 {receiptResult.settledLineCount} 笔 / ¥ {((receiptResult.settledAmountCents ?? 0) / 100).toFixed(2)}
          · 旧卡余额 ¥ {((receiptResult.oldCardBalanceCents ?? 0) / 100).toFixed(2)}
          · 在途 ¥ {((receiptResult.pendingFareCents ?? 0) / 100).toFixed(2)}
          · 案件 {receiptResult.caseStatus}
        </div>
        {#if receiptResult.duplicateOfNote}<div class="note-line">{receiptResult.duplicateOfNote}</div>{/if}
        {#each receiptResult.lines || [] as l, i (i)}
          <div class="note-line">行 {l.eventKey}：{l.action} ¥ {(l.amountCents / 100).toFixed(2)}
            {#if l.action === 'LATE_FARE'}（晚到，{l.clawedBack ? '已从新卡追扣' : '挂账待追'}）{/if}
          </div>
        {/each}
      </div>
    {/if}
  </div>
</div>

<div class="card-panel">
  <h3>💳 充值（挂账后补足新卡余额再追扣）</h3>
  <div class="form-grid">
    <div>
      <label>卡号（默认当前新卡）</label>
      <input bind:value={rechargeCard} />
    </div>
    <div>
      <label>金额（分）</label>
      <input type="number" bind:value={rechargeAmount} />
    </div>
    <div>
      <button class="btn" onclick={doRecharge}>充值</button>
    </div>
    <div class="muted">晚到差额若因新卡余额不足挂账，充值后到客服页点“重试晚到差额追扣”。</div>
  </div>
</div>
