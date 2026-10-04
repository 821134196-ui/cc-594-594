<script>
  import { api, fmtAmt } from '../lib/api.js';
  import { showToast } from '../lib/store.js';

  let cardId = 'C1001';
  let amount = '2.00';
  let gateId = 'GATE-B';
  let minutesAgo = '10';
  let pendingRef = '';
  let receiptId = 'RCP-0001';

  let records = [];
  let logs = [];

  function log(title, detail, kind = 'ok') {
    logs = [{ title, detail: typeof detail === 'string' ? detail : JSON.stringify(detail, null, 2), kind, t: new Date().toLocaleTimeString() }, ...logs];
  }

  async function run(title, fn) {
    try {
      const r = await fn();
      log(title, r);
      return r;
    } catch (e) {
      log(title + '（被拒绝/失败）', e.message, 'err');
      throw e;
    }
  }

  async function refreshRecords() {
    records = await api.get('/sim/offline-records');
  }

  const online = () => run('在线刷卡：' + cardId + ' ¥' + amount, () =>
    api.post('/sim/swipes/online', { cardId, amount, gateId: 'GATE-A' }));

  const offline = async () => {
    const r = await run('离线刷卡（闸机本地记账，不同步不扣款）：' + cardId + ' ¥' + amount + '，乘车于 ' + minutesAgo + ' 分钟前', () =>
      api.post('/sim/swipes/offline', { cardId, amount, gateId, minutesAgo }));
    pendingRef = r.pendingRef;
    await refreshRecords();
  };

  const sync = async () => {
    const r = await run('闸机恢复联网：本地队列同步为在途 PENDING 流水', () =>
      api.post('/sim/gates/sync'));
    await refreshRecords();
    return r;
  };

  const clear = () => run('凭回执清算一笔在途消费（回执 ' + receiptId + '）', () =>
    api.post('/sim/clearing', { pendingRef, receiptId }));

  const clearDup = () => run('用同一回执 ' + receiptId + ' 重复清算（应被幂等忽略）', () =>
    api.post('/sim/clearing', { pendingRef, receiptId }));

  async function reset() {
    const r = await run('重置全部演示数据（C1001 / 张伟 / ¥100）', () =>
      api.post('/dev/reset'));
    cardId = 'C1001';
    await refreshRecords();
    return r;
  }

  /* ---------------- 三个一键演示场景 ---------------- */

  let busy = false;
  async function scenario1() {
    busy = true; logs = [];
    try {
      await reset();
      await run('① 客服核身通过，旧卡 C1001 立即停用', () =>
        api.post('/cs/report-lost', { cardId: 'C1001', idNumber: '110101199001011234' }));
      await run('② 失主持旧卡在闸机 GATE-A 刷卡 → 被拒绝，不产生扣款', () =>
        api.post('/sim/swipes/online', { cardId: 'C1001', amount: '2.00', gateId: 'GATE-A' }));
      const cs = await api.get('/cs/cases');
      log('③ 结果', '旧卡状态 STOLEN，刷卡拒绝事件已记入补卡单 ' + cs[0].id +
        '；补卡单与余额完好，可在客服页继续制卡。');
      showToast('场景一完成：旧卡刷卡被拒绝');
    } catch { /* 日志已展示拒绝原因 */ }
    busy = false;
  }

  async function scenario2() {
    busy = true; logs = [];
    try {
      await reset();
      let r = await run('① 挂失前：闸机离线乘车 ¥5（本地记账）', async () => {
        const x = await api.post('/sim/swipes/offline', { cardId: 'C1001', amount: '5.00', gateId: 'GATE-B' });
        pendingRef = x.pendingRef;
        return x;
      });
      await run('② 闸机恢复，同步为在途 PENDING（挂失时系统已知道这笔）', () =>
        api.post('/sim/gates/sync'));
      await run('③ 客服核身挂失，旧卡停用，系统显示 1 笔在途等待清算', () =>
        api.post('/cs/report-lost', { cardId: 'C1001', idNumber: '110101199001011234' }));
      receiptId = 'RCP-DUP-001';
      await run('④ 制新卡：因仍有在途，余额暂不转移（页面提示等待清算）', async () =>
        api.post('/cs/issue', { replacementId: (await latestCase()).id }));
      await run('⑤ 清算中心送达回执 RCP-DUP-001，在途入账 ¥5，自动转移 ¥95', () =>
        api.post('/sim/clearing', { pendingRef, receiptId }));
      await run('⑥ 清算中心重发同一回执 RCP-DUP-001 → 幂等忽略，未重复扣款/多转', () =>
        api.post('/sim/clearing', { pendingRef, receiptId }));
      const c = await api.get('/cs/cases/' + (await latestCase()).id);
      log('⑦ 对账', '旧卡转出 ¥' + fmtAmt(c.replacement.transferredAmount) +
        ' ＝ 新卡转入 ¥' + fmtAmt(c.newCard.balance) +
        '；新卡余额 ¥' + fmtAmt(c.newCard.balance) + '，重复回执没有多转一分钱。');
      showToast('场景二完成：重复回执不多转');
    } catch { /* 日志已展示 */ }
    busy = false;
  }

  async function scenario3() {
    busy = true; logs = [];
    try {
      await reset();
      await run('① 15 分钟前闸机离线乘车 ¥5（乘车在挂失前，记录一直滞留闸机）', async () => {
        const x = await api.post('/sim/swipes/offline',
          { cardId: 'C1001', amount: '5.00', gateId: 'GATE-B', minutesAgo: '15' });
        pendingRef = x.pendingRef;
        return x;
      });
      await run('② 在线乘车 ¥2（余额 100 → 98）', () =>
        api.post('/sim/swipes/online', { cardId: 'C1001', amount: '2.00', gateId: 'GATE-A' }));
      const rep = await run('③ 客服核身挂失：旧卡停用，此时系统还不知道那笔离线乘车', () =>
        api.post('/cs/report-lost', { cardId: 'C1001', idNumber: '110101199001011234' }));
      await run('④ 闸机才把离线记录同步上来 → 成为 1 笔在途，补卡单重新进入等待清算', () =>
        api.post('/sim/gates/sync'));
      receiptId = 'RCP-LATE-001';
      await run('⑤ 制新卡：有在途未清算，¥98 暂不转移', () =>
        api.post('/cs/issue', { replacementId: rep.id }));
      await run('⑥ 晚到消费清算：原始流水保留并标记“晚到”，登记差额单，可转余额 98 − 5 = 93，自动转入新卡', () =>
        api.post('/sim/clearing', { pendingRef, receiptId }));
      const c = await api.get('/cs/cases/' + rep.id);
      log('⑦ 对账', '挂失快照 ¥98 − 晚到 ¥5 ＝ 转入新卡 ¥' + fmtAmt(c.replacement.transferredAmount) +
        '；旧卡原始 FARE 流水未被修改，另存差额单可追溯。');
      showToast('场景三完成：晚到交易降低可转余额');
    } catch { /* 日志已展示 */ }
    busy = false;
  }

  async function latestCase() {
    const list = await api.get('/cs/cases');
    return list[0];
  }

  refreshRecords();
</script>

<div class="panel">
  <h2>一键演示场景</h2>
  <div class="row">
    <button class="btn danger" on:click={scenario1} disabled={busy}>场景一：旧卡刷卡被拒绝</button>
    <button class="btn warn" on:click={scenario2} disabled={busy}>场景二：重复清算回执不多转钱</button>
    <button class="btn" on:click={scenario3} disabled={busy}>场景三：晚到交易影响可转余额</button>
    <button class="btn ghost" on:click={reset} disabled={busy}>重置演示数据</button>
  </div>
  <p class="muted small" style="margin-bottom:0">
    每个场景都会先重置数据；执行后可到“乘客页”（输入补卡单号）查看脱敏进度，到“客服页”逐笔核对。
  </p>
</div>

<div class="grid2">
  <div class="panel">
    <h2>闸机 / 清算手工操作</h2>
    <div class="row">
      <label>卡号</label><input value={cardId} on:input={(e) => (cardId = e.target.value)} style="width:120px" />
      <label>金额</label><input value={amount} on:input={(e) => (amount = e.target.value)} style="width:90px" />
    </div>
    <div class="row">
      <button class="btn" on:click={online}>在线刷卡（实时鉴权）</button>
    </div>
    <div class="row">
      <label>闸机</label><input value={gateId} on:input={(e) => (gateId = e.target.value)} style="width:110px" />
      <label>乘车于 N 分钟前</label><input value={minutesAgo} on:input={(e) => (minutesAgo = e.target.value)} style="width:70px" />
      <button class="btn ghost" on:click={offline}>离线刷卡（本地记账）</button>
    </div>
    <div class="row">
      <button class="btn ghost" on:click={sync}>闸机同步（本地队列 → PENDING）</button>
      <button class="btn ghost" on:click={refreshRecords}>刷新记录</button>
    </div>
    <div class="row">
      <label>离线引用</label>
      <input value={pendingRef} on:input={(e) => (pendingRef = e.target.value)} style="width:210px" placeholder="PEND-..." />
      <label>回执号</label>
      <input value={receiptId} on:input={(e) => (receiptId = e.target.value)} style="width:160px" />
    </div>
    <div class="row">
      <button class="btn ok" on:click={clear}>清算入账</button>
      <button class="btn warn" on:click={clearDup}>同一回执再清算一次（重复回执测试）</button>
    </div>

    <h3 style="margin-top:14px">闸机离线记录</h3>
    <table>
      <thead><tr><th>引用</th><th>卡</th><th class="num">金额</th><th>同步</th><th>清算</th><th>回执</th></tr></thead>
      <tbody>
        {#each records as r}
          <tr style="cursor:pointer" on:click={() => (pendingRef = r.pendingRef)}>
            <td class="mono">{r.pendingRef}</td><td class="mono">{r.cardId}</td>
            <td class="num">{fmtAmt(r.amount)}</td>
            <td>{r.synced ? '✓' : '—'}</td>
            <td>{r.cleared ? '✓' : '—'}</td>
            <td class="mono small">{r.receiptId || ''}</td>
          </tr>
        {:else}
          <tr><td colspan="6" class="muted">暂无离线记录</td></tr>
        {/each}
      </tbody>
    </table>
  </div>

  <div class="panel">
    <h2>操作日志 / 接口返回</h2>
    {#each logs as l}
      <div style="border-left:3px solid {l.kind === 'err' ? 'var(--bad)' : 'var(--ok)'};
                  padding:6px 10px; margin-bottom:10px; background:{l.kind === 'err' ? 'var(--bad-bg)' : '#f6faf7'}">
        <div class="small muted">{l.t}</div>
        <div><strong>{l.title}</strong></div>
        <pre class="mono small" style="white-space:pre-wrap; margin:6px 0 0;">{l.detail}</pre>
      </div>
    {:else}
      <p class="muted">点击左侧按钮或上方场景，这里显示每一步的请求结果。</p>
    {/each}
  </div>
</div>
