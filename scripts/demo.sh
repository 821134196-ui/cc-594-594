#!/usr/bin/env bash
# =============================================================================
# 三个必看场景的自动演示（需要系统已启动：./start.sh）
#   1) 旧卡挂失后/补卡后刷卡被拒绝
#   2) 重复清算回执不多扣、不多转
#   3) 晚到交易改变可转余额（转账前）+ 转账后晚到则原流水保留、差额从新卡追
# 另含：身份核验、重复补卡拒绝、失败留痕重试、转出=转入校验
# =============================================================================
set -uo pipefail
BASE=http://localhost:8080
J() { python3 -c "import json,sys;d=json.load(sys.stdin);$1"; }
line(){ printf '\n\033[1;36m%s\033[0m\n' "$*"; }

line "重置为种子数据（A 张伟 80.00+在途2.00 / B 李娜 已补卡50.00 / C 王强 转账失败留痕）"
curl -s -X POST $BASE/api/demo/reset | J "print(d['note'])"

line "场景1：旧卡刷卡被拒绝（B 李娜旧卡已 REPLACED）"
curl -s -X POST $BASE/api/gate/swipe -H 'Content-Type: application/json' \
  -d '{"cardNo":"6200000000001002","fareCents":200}' \
  | J "d=d;print('放行?',d['allowed']);print(d['message'])"

line "A 张伟走主流程：错误证件号被拒 -> 正确核验 -> 旧卡停用"
curl -s -X POST $BASE/api/cases/report-loss -H 'Content-Type: application/json' \
  -d '{"cardNo":"6200000000001001","idNo":"000000000000000000"}' | J "print('拒绝:',d.get('error') or d)"
CASE=$(curl -s -X POST $BASE/api/cases/report-loss -H 'Content-Type: application/json' \
  -d '{"cardNo":"6200000000001001","idNo":"110101199001011234","agentId":"A001"}' \
  | J "d=d;print(d['caseNo'])")
echo "案件号: $CASE"

line "场景1（续）：挂失后旧卡再刷被拒绝；重复挂失也被拒绝（一旧卡仅一有效新卡）"
curl -s -X POST $BASE/api/gate/swipe -H 'Content-Type: application/json' \
  -d '{"cardNo":"6200000000001001","fareCents":200}' \
  | J "print('放行?',d['allowed'],'-',d['message'])"
curl -s -X POST $BASE/api/cases/report-loss -H 'Content-Type: application/json' \
  -d '{"cardNo":"6200000000001001","idNo":"110101199001011234"}' \
  | J "print('重复补卡:',d.get('error') or d)"

line "制新卡：进入“余额仍在等待清算”，此时转账被拒绝"
curl -s -X POST $BASE/api/cases/$CASE/issue-new-card | J "c=d['case'];print(c['status'],'-',c['moneyPhase'])"
curl -s -X POST $BASE/api/cases/$CASE/transfer | J "print('提前转账:',d.get('error') or d)"

line "场景2：清算回执 R1 结算在途 2.00，随后重放同一回执（必须幂等、不多扣多转）"
curl -s -X POST $BASE/api/clearing/receipt -H 'Content-Type: application/json' \
  -d "{\"caseNo\":\"$CASE\",\"receiptNo\":\"R1\",\"lines\":[{\"eventKey\":\"E-A-P1\",\"amountCents\":200}]}" \
  | J "print('首次:',d['status'],[(l['action'],l['amountCents']) for l in d['lines']],'旧卡余额',d['oldCardBalanceCents'])"
curl -s -X POST $BASE/api/clearing/receipt -H 'Content-Type: application/json' \
  -d "{\"caseNo\":\"$CASE\",\"receiptNo\":\"R1\",\"lines\":[{\"eventKey\":\"E-A-P1\",\"amountCents\":200}]}" \
  | J "print('重放:',d['status']);print(d['duplicateOfNote'])"

line "场景3a（转账前晚到）：新回执 R0 带一笔此前未上报的离线消费 3.00 → 可转余额降到 75.00"
curl -s -X POST $BASE/api/clearing/receipt -H 'Content-Type: application/json' \
  -d "{\"caseNo\":\"$CASE\",\"receiptNo\":\"R0\",\"lines\":[{\"eventKey\":\"E-A-LATE0\",\"amountCents\":300,\"occurredAt\":\"2026-10-03T07:00:00Z\",\"deviceId\":\"OFFLINE-12\"}]}" \
  | J "print('受理:',d['status'],[(l['action'],l['amountCents']) for l in d['lines']],'可转(旧卡余额)',d['oldCardBalanceCents'])"

line "执行转账：旧卡转出 75.00 = 新卡转入 75.00，原旧卡逐笔流水仍在"
curl -s -X POST $BASE/api/cases/$CASE/transfer | J "
c=d['case'];print('状态:',c['status']);print('旧卡余额:',c['oldCard']['balanceText'],'新卡余额:',c['newCard']['balanceText']);
print('转出/转入一致:',c['transferBalanced'],c['transferredOutCents'],'==',c['transferredInCents'])"

line "场景3b（转账后晚到）：回执 R2 再到一笔 5.00 → 原流水保留+LATE_OFFSET挂平+新卡追扣"
curl -s -X POST $BASE/api/clearing/receipt -H 'Content-Type: application/json' \
  -d "{\"caseNo\":\"$CASE\",\"receiptNo\":\"R2\",\"lines\":[{\"eventKey\":\"E-A-LATE1\",\"amountCents\":500,\"occurredAt\":\"2026-10-02T09:00:00Z\",\"deviceId\":\"OFFLINE-13\"}]}" \
  | J "print([(l['action'],l['amountCents'],'clawedBack='+str(l['clawedBack'])) for l in d['lines']])"
curl -s $BASE/api/cases/$CASE | J "
c=d;print('旧卡余额(应0.00):',c['oldCard']['balanceText'],'| 旧卡流水合计(应0):',c['oldCard']['settledLedgerSumCents']/100)
print('新卡余额(应70.00):',c['newCard']['balanceText'],'| 新卡流水合计(应70):',c['newCard']['settledLedgerSumCents']/100)
a=c['adjustments'][-1];print('差额记录: 原FARE#%d 挂平LATE_OFFSET#%s 追扣LATE_CLAWBACK#%s 状态=%s'%(a['originalFareTxnId'],a['offsetTxnId'],a['clawbackTxnId'],a['status']))"

line "失败不丢：C 王强案件保留 FAILED 与失败尝试，重试即成功（资金守恒）"
CC=$(curl -s $BASE/api/cases | J "[print(c['caseNo']) for c in d if c['holderName']=='王强']")
curl -s -X POST $BASE/api/cases/$CC/transfer | J "
c=d['case'];print('重试结果:',c['status']);print('转移尝试:',[(t['attemptNo'],t['status'],t['amountText']) for t in c['transfers']]);
print('新卡余额:',c['newCard']['balanceText'],'转出=转入:',c['transferBalanced'])"

line "完成。可打开 http://localhost:8080 查看乘客/客服/演示台三个页面。"
