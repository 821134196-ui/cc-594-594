# 公交卡挂失补卡与余额转移系统

乘客丢卡后，由客服核验身份并补卡：系统**先停用旧卡**，**等待在途消费逐笔清算**后计算可转余额，
再把剩余余额**一次性转入新卡**。乘客可查询脱敏后的完整办理进度；客服可追溯每一笔扣款与转移依据。

- 后端：Java 17 · Quarkus 3 · Hibernate ORM · SQLite
- 前端：Svelte 4 · Vite（构建产物打进后端，单端口提供）
- 闸机 / 制卡中心：本地模拟（在线刷卡、离线闸机队列、清算回执、制卡故障）

## 一条命令启动

```bash
./run.sh
```

脚本会在没有 Java/Maven 时自动下载 JDK17 与 Maven（无需 root），构建前后端后单端口启动：

- 系统页面：http://localhost:8080
- 演示账号：卡号 `C1001`，持卡人 张伟，证件号 `110101199001011234`，初始余额 ¥100

开发模式（Quarkus 8080 + Vite 5173 热更新）：

```bash
./run.sh dev
```

命令行演示三个重点场景（服务启动后另开终端）：

```bash
./demo.sh        # 全部三个场景
./demo.sh 1      # 仅场景一：旧卡刷卡被拒绝
./demo.sh 2      # 仅场景二：重复清算回执不多转钱
./demo.sh 3      # 仅场景三：晚到交易影响可转余额
```

页面内「模拟器」标签也有三个**一键演示**按钮；跑完后把输出的补卡单号
（形如 `RPL-XXXXXXXXXX`）填到「乘客 · 办理进度」页即可看到脱敏视图。

## 页面说明

| 标签 | 能做什么 |
| --- | --- |
| 乘客 · 办理进度 | 输入补卡单号，并列查看**旧卡/新卡脱敏流水**、余额转移公式、等待清算还是已转入新卡、完整时间线；等待中自动 3 秒刷新 |
| 客服 · 补卡办理 | 明文卡号核身挂失、制卡（可注入制卡中心故障并重试）、手动转移、新卡再挂失；逐笔查看扣款、回执、转移依据、晚到差额单 |
| 模拟器 · 闸机/清算 | 在线刷卡、离线刷卡（可指定乘车发生时间）、闸机同步、凭回执清算、重复回执；三个一键场景与实时接口日志 |

## 业务规则与落地方式

1. **先停用旧卡**：核身通过立即把卡片置为 `STOLEN`，之后在线刷卡返回
   `REJECTED_CARD_STOLEN`，不产生扣款流水，拒绝事件记入补卡单。
2. **等待在途消费清算**：离线闸机只本地记账（`offline_swipe_records`），同步后成为
   `PENDING` 流水。补卡单存在未清算 PENDING 时，制卡/转移都不放行，页面明确提示
   「仍有 N 笔在途等待清算」。
3. **可转余额公式**：
   `可转余额 = 挂失瞬间余额 − 在途清算合计 − 晚到消费合计`。
4. **旧交易永不修改**：晚到消费保留原始 FARE 流水（金额、发生时间不动），只把状态
   `PENDING → POSTED` 并标记 `late`，另在 `late_differences` 独立登记差额。
5. **失败不丢记录**：制卡中心故障时补卡单置 `FAILED` 但事务提交，余额与单据保留，可重试；
   新卡再次挂失会关闭旧单（`RE_REPORTED`）并沿卡链另开新单。
6. **重复回执幂等**：清算回执 `receiptId` 全局唯一，重复送达直接返回
   `DUPLICATE_RECEIPT_IGNORED` 并留痕，绝不重复扣款。
7. **转移守恒**：余额转移在同一事务内完成，旧卡 `TRANSFER_OUT` 与新卡 `TRANSFER_IN`
   使用同一个金额变量；转移回执唯一，重复转移请求幂等。旧卡转出金额恒等于新卡转入金额。
8. **一对一约束**：`replacements.old_card_id` 数据库唯一约束 + 业务冲突校验，
   一张旧卡只能关联一张有效新卡。
9. **转移后晚到**：极端情况下余额已转走才清算的挂失前消费，旧卡如实变负，差额单挂
   `AFTER_TRANSFER`，沿「补卡单 → 新卡」卡链从当前有效卡追收（`LATE_RECOVERY`）；
   若链头正在补卡，则在其转移前从旧卡余额追收，转出金额等额减少，账目依旧守恒。
10. **挂失后乘车不追扣**：离线乘车若发生时间晚于挂失时刻，清算时置 `REJECTED`，
    原始记录保留，不模糊扣款。

## 数据模型（SQLite，`data/buscard.db`）

| 表 | 说明 |
| --- | --- |
| `cards` | 卡片：余额、状态（ACTIVE/STOLEN/REPLACED）、证件号哈希（明文不落库）、乐观锁 |
| `card_transactions` | 只追加流水：TOPUP/FARE/TRANSFER_OUT/TRANSFER_IN/LATE_RECOVERY，POSTED/PENDING/REJECTED，回执与离线引用唯一 |
| `replacements` | 补卡单聚合：挂失快照、在途/晚到合计、可转/已转金额、转移回执、制卡尝试次数与失败原因 |
| `replacement_events` | 办理进度事件（只追加），乘客时间线与客服追溯同源 |
| `offline_swipe_records` | 闸机离线本地队列：同步/清算标志、回执 |
| `late_differences` | 晚到差额：转移前冲减 / 转移后追账 / 已追收 |

## 主要接口

```
POST /api/cs/report-lost      {cardId, idNumber}              核身挂失停用
POST /api/cs/issue            {replacementId, simulateFailure} 制新卡（可模拟故障）
POST /api/cs/transfer         {replacementId, receiptId}      在途清零后转移
POST /api/cs/re-report-lost   {cardId, idNumber}              新卡再挂失（沿卡链开新单）
GET  /api/cs/cases            /api/cs/cases/{id}              客服全量追踪（明文+逐笔依据）
GET  /api/passenger/cases/{id}                                乘客脱敏进度
POST /api/sim/swipes/online   {cardId, amount, gateId}        在线刷卡（停用即拒）
POST /api/sim/swipes/offline  {cardId, amount, gateId, minutesAgo} 离线本地记账
POST /api/sim/gates/sync                                      闸机队列同步为 PENDING
POST /api/sim/clearing        {pendingRef, receiptId}         凭回执清算（重复幂等）
POST /api/dev/reset                                           重置为初始演示数据
```

## 项目结构

```
src/main/java/com/buscard/
  model/      Card / CardTransaction / Replacement / ReplacementEvent /
              OfflineSwipeRecord / LateDifference
  service/    ReplacementService（补卡/转移/卡链追账） GateService（闸机/清算）
              CardOfficeService（制卡模拟） QueryService（乘客/客服视图） IdService SeedData
  web/        REST 资源与统一错误映射
web/src/      Svelte 页面（pages/）与组件（components/）
run.sh  demo.sh  README.md
```
