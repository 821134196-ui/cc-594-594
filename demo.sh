#!/usr/bin/env bash
# 公交卡挂失补卡系统 —— 三个重点场景命令行演示
#   ./demo.sh                # 演示全部三个场景
#   ./demo.sh 1|2|3          # 只演示某个场景
# 依赖：curl、python3；服务需已启动（./run.sh）
set -uo pipefail
cd "$(dirname "$0")"

BASE="${BUSCARD_BASE:-http://127.0.0.1:8080}"
ID_NO="110101199001011234"
CARD="C1001"

c_green=$'\033[32m'; c_red=$'\033[31m'; c_yellow=$'\033[33m'
c_cyan=$'\033[36m'; c_bold=$'\033[1m'; c_off=$'\033[0m'

step()  { printf '\n%s▸ %s%s\n' "$c_cyan" "$*" "$c_off"; }
ok()    { printf '%s  ✓ %s%s\n' "$c_green" "$*" "$c_off"; }
warn()  { printf '%s  ! %s%s\n' "$c_yellow" "$*" "$c_off"; }
fail()  { printf '%s  ✗ %s%s\n' "$c_red" "$*" "$c_off"; }
title() { printf '\n%s══ %s ══%s\n' "$c_bold" "$*" "$c_off"; }

api()  { curl -s -H 'Content-Type: application/json' "$@"; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1"; }

wait_up() {
  for _ in $(seq 1 60); do
    curl -s -o /dev/null "$BASE/api/dev/cards" && return 0
    sleep 1
  done
  echo "服务未启动：先运行 ./run.sh（$BASE）"; exit 1
}

reset() { api -XPOST "$BASE/api/dev/reset" >/dev/null; }

# ======================================================================
scenario1() {
  title "场景一：旧卡挂失后刷卡被拒绝（不产生扣款）"
  reset
  step "客服核身（证件号 $ID_NO）并挂失停用旧卡"
  RPL=$(api -XPOST "$BASE/api/cs/report-lost" -d "{\"cardId\":\"$CARD\",\"idNumber\":\"$ID_NO\"}" | jget 'd["id"]')
  ok "补卡单 $RPL 已建立，旧卡立即停用"

  step "失主持旧卡到闸机 GATE-A 刷卡乘车 ¥2"
  RES=$(api -XPOST "$BASE/api/sim/swipes/online" -d '{"cardId":"C1001","amount":"2.00","gateId":"GATE-A"}')
  echo "$RES" | python3 -m json.tool
  ACCEPTED=$(echo "$RES" | jget 'd["accepted"]')
  REASON=$(echo "$RES" | jget 'd["reason"]')
  [ "$ACCEPTED" = "False" ] && ok "闸机拒绝刷卡（$REASON），未发生扣款" || fail "应当被拒绝"

  step "核对：旧卡没有任何新增 FARE 流水，拒绝事件记入补卡单"
  api "$BASE/api/cs/cases/$RPL" | python3 -c "
import sys,json
d=json.load(sys.stdin)
fares=[t for t in d['oldTransactions'] if t['type']=='FARE']
rejects=[e for e in d['events'] if e['type']=='SWIPE_REJECTED']
assert len(fares)==0, fares
assert rejects, '缺少 SWIPE_REJECTED 事件'
print('  %s旧卡 FARE 流水 0 笔；拒绝事件 %d 条（%s）%s' % ('\033[32m', len(rejects), rejects[0]['message'], '\033[0m'))
"

  step "重复补卡防护：再次对该卡挂失应被拒绝"
  api -XPOST "$BASE/api/cs/report-lost" -d "{\"cardId\":\"$CARD\",\"idNumber\":\"$ID_NO\"}" \
    | jget 'd["error"]' | sed 's/^/  /'
}

# ======================================================================
scenario2() {
  title "场景二：重复清算回执不得多转钱（转出 = 转入）"
  reset
  step "① 挂失前：闸机 GATE-B 离线乘车 ¥5（只在闸机本地记账）"
  REF=$(api -XPOST "$BASE/api/sim/swipes/offline" -d '{"cardId":"C1001","amount":"5.00","gateId":"GATE-B"}' | jget 'd["pendingRef"]')
  ok "离线记录 $REF"

  step "② 闸机恢复，同步为在途 PENDING 流水（挂失时系统已知这笔）"
  api -XPOST "$BASE/api/sim/gates/sync" | jget '"同步 "+str(d["synced"])+" 笔"' | sed 's/^/  /'

  step "③ 客服核身挂失，旧卡停用，显示 1 笔在途等待清算"
  RPL=$(api -XPOST "$BASE/api/cs/report-lost" -d "{\"cardId\":\"$CARD\",\"idNumber\":\"$ID_NO\"}" | jget 'd["id"]')
  ok "补卡单 $RPL"

  step "④ 制出新卡：因在途未清，余额保留不转移"
  NC=$(api -XPOST "$BASE/api/cs/issue" -d "{\"replacementId\":\"$RPL\"}" | jget 'd["newCardId"]')
  ok "新卡 $NC 已与旧卡一对一关联；此时尝试手动转移会被拒绝"
  api -XPOST "$BASE/api/cs/transfer" -d "{\"replacementId\":\"$RPL\",\"receiptId\":\"X\"}" | jget 'd["error"]' | sed 's/^/  /'

  step "⑤ 清算中心送达回执 RCP-DUP-001：在途入账 ¥5，可转 95 并自动转移"
  api -XPOST "$BASE/api/sim/clearing" -d "{\"pendingRef\":\"$REF\",\"receiptId\":\"RCP-DUP-001\"}" | jget 'd["outcome"]' | sed 's/^/  清算结果：/'

  step "⑥ 清算中心故障重发，同一回执 RCP-DUP-001 再次送达"
  DUP=$(api -XPOST "$BASE/api/sim/clearing" -d "{\"pendingRef\":\"$REF\",\"receiptId\":\"RCP-DUP-001\"}")
  echo "$DUP" | jget 'd["outcome"]' | sed 's/^/  第二次结果：/'
  [ "$(echo "$DUP" | jget 'd["duplicate"]')" = "True" ] && ok "重复回执被幂等忽略，没有重复扣款、没有多转钱"

  step "⑦ 对账：旧卡转出金额 必须等于 新卡转入金额"
  api "$BASE/api/cs/cases/$RPL" | python3 -c "
import sys,json
d=json.load(sys.stdin); r=d['replacement']
out=[t for t in d['oldTransactions'] if t['type']=='TRANSFER_OUT'][0]
inn=[t for t in d['newTransactions'] if t['type']=='TRANSFER_IN'][0]
print('  挂失快照 ¥%s − 在途清算 ¥%s − 晚到 ¥%s = 可转 ¥%s' % (
  r['reportedBalance'], r['pendingSettledTotal'], r['lateTotal'], r['transferableAmount']))
print('  旧卡转出 ¥%s（回执 %s）' % (out['amount'], out['receiptId']))
print('  新卡转入 ¥%s（回执 %s）' % (inn['amount'], inn['receiptId']))
assert float(out['amount'])==float(inn['amount'])==float(r['transferredAmount'])==95.0
assert float(d['oldCard']['balance'])==0 and float(d['newCard']['balance'])==95.0
assert any(e['type']=='DUPLICATE_RECEIPT' for e in d['events'])
print('  \033[32m✓ 守恒成立：95.00 = 95.00；旧卡归零作废，重复回执事件留痕\033[0m')
"
  echo "    补卡单号（可到乘客页查询）：$RPL"
}

# ======================================================================
scenario3() {
  title "场景三：晚到交易影响可转余额（不改旧流水，差额独立登记）"
  reset
  step "① 15 分钟前闸机离线乘车 ¥5（发生在挂失前，记录一直滞留闸机）"
  REF=$(api -XPOST "$BASE/api/sim/swipes/offline" \
        -d '{"cardId":"C1001","amount":"5.00","gateId":"GATE-B","minutesAgo":"15"}' | jget 'd["pendingRef"]')
  ok "离线记录 $REF（乘车时间早于挂失）"

  step "② 另一笔在线乘车 ¥2，余额 100 → 98"
  api -XPOST "$BASE/api/sim/swipes/online" -d '{"cardId":"C1001","amount":"2.00"}' >/dev/null

  step "③ 客服核身挂失：此刻系统还不知道那笔离线消费，挂失快照 = ¥98"
  RPL=$(api -XPOST "$BASE/api/cs/report-lost" -d "{\"cardId\":\"$CARD\",\"idNumber\":\"$ID_NO\"}" | jget 'd["id"]')
  ok "补卡单 $RPL"

  step "④ 闸机恢复，晚到的离线记录才同步上来 → 补卡单重新进入等待清算"
  api -XPOST "$BASE/api/sim/gates/sync" | jget '"迟到同步 "+str(d["synced"])+" 笔"' | sed 's/^/  /'

  step "⑤ 制新卡：存在 1 笔在途，¥98 暂不转移"
  api -XPOST "$BASE/api/cs/issue" -d "{\"replacementId\":\"$RPL\"}" \
    | jget '"状态 "+d["status"]+"，可转余额 ¥"+str(d["transferableAmount"])' | sed 's/^/  /'

  step "⑥ 晚到消费清算：原始 FARE 流水保留并标记“晚到”，另记差额单，自动转移 98−5=93"
  api -XPOST "$BASE/api/sim/clearing" -d "{\"pendingRef\":\"$REF\",\"receiptId\":\"RCP-LATE-001\"}" \
    | jget 'd["outcome"]' | sed 's/^/  清算结果：/'

  step "⑦ 对账：转移前后余额变化与原始流水逐笔可核对"
  api "$BASE/api/cs/cases/$RPL" | python3 -c "
import sys,json
d=json.load(sys.stdin); r=d['replacement']
fare=[t for t in d['oldTransactions'] if t['type']=='FARE' and t.get('late')][0]
out=[t for t in d['oldTransactions'] if t['type']=='TRANSFER_OUT'][0]
inn=[t for t in d['newTransactions'] if t['type']=='TRANSFER_IN'][0]
diff=d['differences'][0]
print('  原始 FARE 流水：%s 金额 ¥%s 状态 %s 晚到=%s（金额与发生时间均未被修改）' % (
  fare['id'], fare['amount'], fare['status'], fare['late']))
print('  差额单：%s ¥%s，处理：%s' % (diff['id'], diff['amount'], diff['resolution']))
print('  ¥98 挂失快照 − ¥5 晚到 = ¥93；转出 ¥%s，转入 ¥%s' % (out['amount'], inn['amount']))
assert float(r['reportedBalance'])==98 and float(r['lateTotal'])==5
assert float(out['amount'])==float(inn['amount'])==93.0
assert fare['status']=='POSTED' and fare['late'] and diff['resolved']
print('  \033[32m✓ 旧交易原样保留、差额独立登记、可转余额 93.00 守恒转入新卡\033[0m')
"
  echo "    补卡单号（可到乘客页查询）：$RPL"
}

wait_up
case "${1:-all}" in
  1) scenario1 ;;
  2) scenario2 ;;
  3) scenario3 ;;
  all) scenario1; scenario2; scenario3 ;;
  *) echo "用法：./demo.sh [1|2|3]"; exit 1 ;;
esac
printf '\n%s演示完成。浏览器打开 %s ，在“乘客 · 办理进度”输入补卡单号可查看脱敏进度。%s\n' "$c_bold" "$BASE" "$c_off"
