# EZMiner 可优化方向

- **分析对象**：`master` @ `8641428`
- **分析日期**：2026-10-03
- **方法**：静态阅读热点路径 + 逐项核对调用方/默认值。**未做 profiling**，所有收益为基于代码结构的推断，落地前需用仓库内自带的 spark（`libs/spark-1.10.19.jar`）实测
- **配套文档**：[`codebase-analysis.md`](./codebase-analysis.md)（现状分析）、[`todo.md`](./todo.md)（既有待办）

---

## 0. 结论速览

| # | 方向 | 类型 | 预估收益 | 成本 | 风险 |
|---|---|---|---|---|---|
| 1 | **默认开启 `useChunkCachedHarvest`** | 性能 | 每方块省 2 次 chunk 查找 | 改默认值 + 复测 | 低 |
| 2 | **邻居通知批处理去装箱 + 合并双趟遍历** | 性能 | 每批次少 3N 次装箱、省一趟遍历 | 小 | 低 |
| 3 | **客户端预览网格增量更新** | 性能 | 重建从 O(N) 降到 O(1) 摊销 | 中 | 中 |
| 4 | **`PacketCachedBlockSync` 改增量下发** | 性能 | 预计算期间流量 O(N²)→O(N) | 中 | 中 |
| 5 | **删除 `BlockHarvestActionExecutor.executeBatch` 重复实现** | 可维护性 | 消除双份维护 | 小 | 低 | ✅ 2026-10 |
| 6 | **接入已有 3 个 JUnit 测试到 build/CI** | 可维护性 | 形成回归网 | 极小 | 低 | ✅ 2026-10（见下方修正） |
| 7 | **`onWorldUnload` 加维度过滤 + 掉落 flush** | 正确性 | 消除物品损失路径 | 小 | 低 | ✅ 2026-10 |
| 8 |  每 tick 小对象分配（`ChunkCachedHarvester` 等） | 性能 | 降低 GC 抖动 | 小 | 低 |

> 优先级建议：**6 → 5 → 7 → 1 → 2**，前四项合计不到一天且风险极低；3/4 需要设计，留待实测确认收益后再动。

> **更新记录（2026-10）**：第 5、7 项已完成；第 6 项按下方「修正」以另一种方式完成。
> 第 1 项经用户决定**暂不改默认值**（见 §1.1 的更新说明）。其余条目仍然有效。

---

## 1. 性能方向

### 1.1 【P1】默认路径未启用 chunk 缓存

**现状**：`Config.useChunkCachedHarvest` 默认 **`false`**（`Config.java:291`）。即默认走 `BaseOperator.processBatchWithBatchedExhaustion` → `BlockHarvestActionExecutor.execute`，每方块执行：

```java
world.getBlock(x, y, z)        // ① ChunkProviderServer → LongHashMap 查找
world.getBlockMetadata(x, y, z) // ② 再一次
fastMgr.ezminer$tryHarvestBlockFast(...)  // ③ setBlock 内部第三次
```

而 `ChunkCachedHarvester`（`chain/execution/ChunkCachedHarvester.java`）在 chunk / 子区块切换时才解析一次引用，逐方块只做数组读：

```java
Block block = currentEbs.getBlockByExtId(lx, ly, lz);   // 数组读
ChunkBlockWriteHelper.writeAirToEbs(currentChunk, currentEbs, x, y, z);
```

**优化**：该类自身的 javadoc 已量化「每方块省 2 次 `getChunkFromChunkCoords` + 1 次 `getBlockStorageArray`」，且 BFS 输出天然局部性聚集，chunk 切换次数远低于方块数。

**验证方式**：`Config.useChunkCachedHarvest` 已存在，直接 A/B 对比即可，无需改代码就能先测。

> **更新（2026-10）：默认值暂不改动。** 配置文案写着「EXPERIMENTAL — Skips some safety checks」，
> 与代码结构不符；已就此做了逐行差异调查，结论见 **§6**——该路径确实绕过了 `World.setBlock`
> 这一层，是否可接受取决于是否使用领地保护/方块日志类模组。**先实测再决定**，不要在未验证的
> 情况下翻转默认值（且已安装用户的配置文件里已写入 `false`，改 Java 默认值只影响新装用户）。

> **跨 chunk 边界注意**：`ChunkCachedHarvester` 的 `touchedColumns` 只在 `flushCurrentChunk` 时
> 统一更新高度图，开启前需确认跨 chunk 批次的边界情形。

---

### 1.2 【P2】邻居通知批处理的装箱与双趟遍历

**位置**：`ChunkBlockWriteHelper.notifyBatchNeighborChange`

```java
java.util.Set<Long> notified = new java.util.HashSet<>(Math.max(16, removed.size() * 3));
...
long key = ((long) nx << 40) | ((long) nz << 8) | (long) (ny & 0xFF);
if (!notified.add(key)) continue;      // ← 每个邻居坐标装箱一个 Long
```

该方法是**默认开启**的（`notifyNeighborsOnChainBreak = true`），每批次为 `6 × N` 个邻居坐标各装箱一个 `Long`。64 块/批 → 384 次装箱/次，每秒 20 次 → 7680 次装箱/秒。

同一文件后面还有**第二趟完整遍历**：

```java
for (RemovedBlock rb : removed) {
    com.czqwq.EZMiner.compat.CoFHWaterBridge.sweepFloatingWaterAbove(world, rb.x, rb.y, rb.z);
}
```

**优化（两项，互不冲突）**：
1. 换用 fastutil `LongOpenHashSet`——项目**已声明该依赖**（`dependencies.gradle` 的 `compileOnly('it.unimi.dsi:fastutil:8.5.18')`），且 `BasePositionFounder` 已在用，属一致性修复；
2. 把 `sweepFloatingWaterAbove` 合并进第一趟循环的 `RemovedBlock` 迭代，省一趟遍历与一次边界统计的重复扫描。

**顺带修正**：该方法的 javadoc 写「Caller gates this behind `Config.notifyNeighborsOnChainBreak` (**default off**)」，与实际默认值 `true` 矛盾，应一并改正。

**实测提示**：`sweepFloatingWaterAbove` 在实心岩层中于第一个 `world.getBlock` 后即 `break`（见 `compat/CoFHWaterBridge.java`），所以其成本只在「挖空后上方是空气柱」时才是 `MAX_SWEEP` 量级。**不要**为它做提前剪枝优化——收益取决于地形。

---

### 1.3 【P2】客户端预览网格：全量重建 + 全量 GPU 上传

**现状**：`MinerRenderer.onRenderWorldLast` 由 `RenderWorldLastEvent` 驱动（**每帧**），其中 `drainQueue` 在 `spaceCalc.hasChange` 时执行全量重建：

```java
if (spaceCalc.hasChange) {
    SpaceCalculator.VertexAndIndex vi = spaceCalc.getVertexAndIndex();  // O(N) 全量
    renderCache.updateData(vi.vertices, vi.indices);                    // 全量 GPU 上传
}
```

`SpaceCalculator.getVertexAndIndex()` 对**每一个**位置重新计算 12 条边，并逐块分配：

- `VERTEX.clone()` → `float[24]` / 块
- `new int[kept * 2]` / 块
- `ArrayList<float[]>` / `ArrayList<int[]>` 累积
- 再由 `ArrayConverter.convertF/convertI` 整体复制一次

之后 `RenderCache.updateData` 又复制进 staging buffer 并 `glBufferData` 全量重传。

**成本界定（避免夸大）**：预览搜索由 `parallelTick.processNormalTasks()` 在 **client tick（20/s）** 驱动，不是每帧；`previewBlockLimit` 默认 **256**（`Config.java:375`）。所以默认配置下约 20 次重建/秒 × 256 块 = 5120 块处理/秒 + 5120 次小数组分配/秒——**可接受**。风险在于用户把 `previewBlockLimit` 调高时，重建成本随 N 线性、GPU 上传也随 N 线性，且每次重建都阻塞渲染线程。

**优化（按性价比排序）**：

1. **合并两次拷贝**：让 `SpaceCalculator` 直接写入可增长的 `float[]` / `int[]` 暂存数组，跳过 `ArrayList<float[]>` 中间层与 `ArrayConverter`。改动局部、无行为变化。
2. **时间片合并**：搜索进行中时限制重建频率（如 ≥100 ms 一次），搜索完成时（`searchComplete`）强制重建一次收尾。把 20 次/秒压到 ≤10 次/秒，且不损失最终正确性。
3. **真正的增量更新**（收益最大、成本最高）：预览期间位置**只增不减**，新增一块只影响自身与 6 个邻居的可见边。可维护扁平数组 + 仅重写受影响块的 index 区段，把每次重建从 O(N) 降到 O(1)。

**建议**：先做 2（最安全、立刻见效），实测后再决定是否投入 3。

---

### 1.4 【P2】`PacketCachedBlockSync` 每次重发全量列表

**位置**：`ChainPreCalcEngine.tickBfs` 末尾

```java
dirty = true;
EZMiner.network.network.sendTo(new PacketCachedBlockSync(results, center.x, center.y, center.z, dimension), player);
```

BFS 每 tick 处理上限 `CHECKS_PER_TICK = 2048`，而每次 tick 都把**当前累积的完整 `results` 列表**序列化下发（`PacketCachedBlockSync.toBytes` 逐位置写 3 个 int）。设 BFS 跨 T 个 tick 完成、每 tick 后列表长度为 Nᵢ，则总传输量为 `Σ Nᵢ ≈ O(T × N)`——典型的 O(N²/T) 模式。

**优化**：改为增量下发——服务端记录「客户端已有多少个位置」，每次只发 `results[已发送..size)` 这段。客户端按序 append 即可，收尾包（`done`）语义不变。

**成本界定**：默认 `blockLimit` 下 T 很小（2048 checks/tick 通常 1~3 tick 完成），所以绝对量不大；**该优化的重要性随 `blockLimit` 增长而上升**。建议与 3 一起在实测确认 blockLimit 较大场景后再做。

---

### 1.5 【P3】每 tick 小对象分配

| 位置 | 分配物 | 频率 |
|---|---|---|
| `BaseOperator.processBatchChunkCached` | `new ChunkCachedHarvester()`（内含 `boolean[256]`） | 每玩家每 tick |
| `BaseOperator` 两个 batch 方法 | `new ArrayList<>(min(perTick,64)*2)` 作 `removedSink` | 每玩家每 tick（`notifyNeighborsOnChainBreak` 开启时） |
| `ChunkBlockWriteHelper.ChunkBatch` | `new boolean[256]` `touchedColumns` | 每 chunk 每批 |

**优化**：`ChunkCachedHarvester` 与 `removedSink` 提升为 `BaseOperator` 字段，每 tick 开头 `reset()` 而非 `new`。收益不大（降低年轻代分配速率），但改动极小且无行为风险。

---

### 1.6 已核查、**不建议**优化的项

记录下来避免重复投入：

| 项 | 结论 |
|---|---|
| `ItemFilterExpression.cached()` | 已是 `volatile` 字段 + 引用比较的恒等缓存（`utils/ItemFilterExpression.java:70`），热路径零成本 |
| `flagNeighbouringLeavesForDecay` | 非木质方块在 `Material.wood` 检查处直接 return（`ChunkBlockWriteHelper` 内），O(1) |
| `updateHeightMap` | 向下扫描在遇到第一个非空气方块时立即 `break`；矿脉场景下地表方块立刻终止扫描，**不是**热点 |
| `notifyBatchNeighborChange` 的 6 邻居枚举 | 成本与挖掘区域**表面积**成正比而非体积，注释已说明；这是正确性必需 |
| `sweepFloatingWaterAbove` | 见 1.2 的实测提示，实心岩层中首轮即退出 |
| `ChainPositionFounder.collectNeighbours` | 原子准入已前置到收集线程，每邻居一次哈希——当前实现已是最优形态 |
| `MainThreadEnforcer.DEFERRED` | `ConcurrentLinkedQueue` + 每 tick 排空上限 1024，无优化空间 |

---

## 2. 可维护性方向

### 2.1 【P1】删除 `BlockHarvestActionExecutor` 的两处重复实现

`executeBatch()`（`:117-243`）与 `executeWithPreResolved()`（`:250-268`）**均无调用方**，而 `executeBatch` 的循环体与 `ChunkCachedHarvester.harvestNext`（`:95-229`）是近乎逐行的双胞胎，重复了 TiC `beforeBlockBreak`、剪刀 hook、GT5 耐久预检、工具损耗、EBS 写空、树叶衰减、掉落（含 Natura 特判）、XP 全部逻辑。

**风险实证**：`8641428`（末地传送门修复）这类改动必须在**两处同步**才生效——漏一处即静默失效。这是当前代码库最高的维护风险点。

**优化**：删除两个死方法；`ChunkCachedHarvester` 成为唯一的批量路径。若担心未来需要批量接口，让 `ChunkCachedHarvester` 实现 `ChainActionExecutor` 即可，而不是保留一份漂移的副本。

> **✅ 已完成（2026-10）**：两个方法已删除，9 个失效 import 一并清理，`ChunkCachedHarvester` 现为
> 唯一批量实现。级联孤儿 `ChunkBlockWriteHelper.groupByChunk` / `ChunkBatch` 也一并删除。
> `execute(Vector3i, EntityPlayerMP)` 保留，`ChainActionExecutor` 接口不受影响。

### 2.2 【P1】清理规划子系统脚手架

`chain/mode/`（7 文件 ~200 行）注册到 registry 但 `.get()` / `.all()` **零调用方**；`chain/planning/` 的 `ChainPlanner` / `ChainPlanningStrategy` / `ChainBlockMatcher` / `ChainCandidateFilter` / `ChainTraverser` 无实现类，`ChainPlanningRuntimeFactory.create()` 零调用方。

**优化**：整体删除（`AGENTS.md` 与 `docs/review-summary.md` 已将其标注为 write-only，仅缺执行）。若确有未来规划，把设计写进 `docs/todo.md` 而非留可编译的死接口。

> **✅ 已完成（2026-10）**：`chain/mode/` 7 个文件、`chain/planning/` 5 个脚手架文件与
> `ChainPlanningRuntimeFactory.create` 均已删除，连带清理 `EZMiner.java` / `CommonProxy.java` 各
> 2 处引用。经核实**无孤儿 lang key**——死 bootstrap 引用的 18 个 `ezminer.mode.*` 全部同时被活代码
> `MinerModeState` 使用。删除还消除了一处已漂移的第二真源（死 bootstrap 的 blast 只有 5 个子模式，
> 活 `MinerModeState` 有 7 个）。

### 2.3 【P2】`Config` 与 `EZMinerConfigGui` 数据驱动化

`Config.java` 1913 行 / 112 个 `public static` 字段；`EZMinerConfigGui.java` 2206 行，行→字段映射重复在 5 个方法中。

**优化**：按 `docs/todo.md` #5 引入 `ConfigRowDef{i18nKey, row, section, widgetType, getter, setter, default}` 描述符表驱动，新增配置项从「改 10+ 处」降为「加一行」。

**前置动作**：先修 `PacketServerConfig` 的同步缺口（19 个旧服务端字段未同步，专用服 OP 保存 GUI 会静默重置）——否则数据驱动化会把该 bug 一起固化进描述符表。

### 2.4 【P2】`core` ↔ `chain` 双向依赖

chain→core 37 处 import、core→chain 31 处；`chain.planning` 直接 import 12 个 `core.founder` 类。

**优化**：chain 定义 `ChainPlanningTask` / `ChainActionExecutor` 等接口，core 提供实现，依赖单向化。与 2.1/2.2 同批做最省事——删掉死类后剩余耦合面更小、更易看清。

---

## 3. 正确性方向

### 3.1 【P1】`onWorldUnload` 过度清理 + 丢弃掉落

`chain/lifecycle/ChainLifecycleService.java:45` 不区分是哪个 world 卸载：

```java
public void onWorldUnload(Map<UUID, Manager> managers) {
    EZMiner.chainStateService.onWorldUnload();      // 清空所有玩家状态
    for (Manager mgr : managers.values()) stopRuntime(mgr);   // 停止所有连锁
}
```

`stopRuntime` → `mgr.clearDrops()` 会**丢弃已收集但尚未生成的掉落物**。

**优化**：加维度过滤（只清理与该 world 维度相关的 `Manager`），并把 `clearDrops()` 改为先尝试 `flushDrops()`。改动小、语义明确。

> **✅ 已完成（2026-10）**：改为按**世界对象同一性**过滤（`mgr.player.worldObj != world` 则跳过），
> 比维度号更直接，在「维度卸载」与「服务器关闭」两种语义下都正确。经比对，`Manager.cleanupState()`
> 是 `ChainStateService.clearRuntime()` 的**超集**，因此删除了冗余的 `ChainStateService.onWorldUnload()`。
>
> **附加收益**：维度过滤本身就消除了掉落丢弃问题——一个维度卸载时其中已无玩家，过滤后不会有任何
> `Manager` 被停止。真正在卸载世界里挖矿的玩家在「服务器关闭」场景下掉不掉落已无意义。

**关联发现（同类，2026-10 一并修复）**：`Manager.player` **从不**在重生/换维度时刷新，而 1.7.10
两者都会创建**新的 `EntityPlayerMP` 实例**。陈旧引用使 `Manager.onWorldTick` 的世界比较失效，
换维度后该玩家的扫雷/探矿 tick 模式与预计算**静默失效**直到下次开链；`EZMinerAPI.startChain` 也会在
旧世界里查 `blockExists` 而误判失败。已新增 `Manager.updatePlayer`，由
`ChainLifecycleService.refreshAndCleanup` 在 cleanup **之前**调用（`clearDrops` 依赖该引用清 XP）。

---

## 4. 构建 / 验证方向

### 4.1 【P1】接入已有测试

`src/test/java/com/czqwq/EZMiner/core/founder/` 下已有 3 个纯逻辑 JUnit 测试（`ShellGeometryTest`、`EncodePosTest`、`BfsConsistencyTest`），`dependencies.gradle` 已声明 `testImplementation('junit:junit:4.13.2')`，但 `build` 任务与 CI workflow 都未接入。

**优化**：把 `test` 挂进 `build` 或 CI（`.github/workflows/build-and-test.yml`）。这是**全库性价比最高的一项**：测试已写好，接入成本近乎为零，且直接守护 `encodePos` 位编码与壳几何这两个最容易出静默错误的算法。

**同时修正**：`AGENTS.md` 的「**No tests exist**」已过时。

> **⚠ 上述判断有误，2026-10 更正**：这三个测试**并不调用生产类**，而是把算法内联复制了一份
> （`EncodePosTest` 自带一份 `encodePos` 实现，`ShellGeometryTest` 自带壳枚举，`BfsConsistencyTest`
> 自带两份 BFS），目的是避开 Minecraft 类加载。**改坏生产实现它们不会报错**——所以按原样接入
> CI 并不构成回归网，原文「直接守护 encodePos 与壳几何」的说法是错的。
>
> **✅ 已完成（2026-10）**：
> 1. `encodePos` 已抽到纯函数 `utils/PosEncoding.encode`（不依赖任何 Minecraft 类），生产代码
>    （`BasePositionFounder`、`ChainPositionFounder`、`ChainPreCalcEngine`、
>    `TinkersConstructLevelingBridge`）全部改为委托它，原 `BasePositionFounder.encodePos` 已删除
>    —— 单一真源。
> 2. `EncodePosTest` 改写为调用真实的 `PosEncoding.encode`。**已做变异测试验证**：把 `encode` 的
>    移位从 38 改成 37 后，`differentCoordsProduceDifferentKeys` 立即失败——该测试现在真的守护
>    生产代码。
> 3. `ShellGeometryTest` / `BfsConsistencyTest` 保留内联副本（这两个算法与世界读取深度纠缠，无法
>    低成本抽出），但已在其 javadoc 中**明确标注**「验证的是副本，回归不会失败」，避免后人误判。
> 4. **接线无需改动**：实测确认 `./gradlew build` 经 `check` → `test` 已会运行测试，三个测试类
>    共 27 个用例全部通过。原先 `build/` 下没有 `classes/java/test` 只是因为测试源码从未被编译过。

### 4.2 【P3】profile 前置项

`docs/todo.md` #1 已要求「profiling `useDualFrontierBfs` / `usePrimitiveVisitedSet`」。仓库内自带 `libs/spark-1.10.19.jar`，可直接出火焰图。

**建议**：本文档 1.3 / 1.4 两项在动手前先借此实测——若实测显示预览重建与预计算包不是热点，则应放弃而非盲目重构。

---

## 5. 建议路线图

**第一批（低风险、无需实测）— ✅ 已完成（2026-10）**
1. ~~接入 JUnit 到 build/CI~~ → 更正为：抽出 `PosEncoding` 让测试真正守护生产代码（4.1），构建接线实测已存在
2. ✅ 删除死代码：`executeBatch` / `executeWithPreResolved` / `chain/mode/` / planner 脚手架（2.1、2.2）
3. ✅ `onWorldUnload` 世界过滤（3.1），并附带修复 `Manager.player` 陈旧引用
4. ✅ `AGENTS.md` 文档修正（测试描述、脚手架已删除、`notifyBatchNeighborChange` 的 default off 注释）

**验证结果**：`./gradlew spotlessApply build test` → BUILD SUCCESSFUL；含 checkstyle、reobf；
27 个测试用例全部通过；变异测试确认 `EncodePosTest` 能捕获生产回归。

**第二批（实测驱动，约一天）**
5. A/B 验证并考虑开启 `useChunkCachedHarvest`（1.1）
6. 邻居通知的 fastutil 化与双趟合并（1.2）
7. 每 tick 小分配提升为字段（1.5）

**第三批（设计 + 实测确认收益后）**
8. 客户端预览网格：先做时间片合并，实测后再评估增量更新（1.3）
9. `PacketCachedBlockSync` 增量化（1.4）
10. `Config` / GUI 数据驱动化 + 同步缺口修复（2.3）

---

## 6. `useChunkCachedHarvest` 的「跳过安全检查」差异调查（2026-10）

**问题**：`Config.java:898-903` 的文案写着「EXPERIMENTAL — Enables a faster block-harvest path
during chain mining. **Skips some safety checks** for maximum speed. ⚠ Use with caution.」，
但代码阅读显示两条路径的方块级检查项是等价的。查清「跳过的是什么」。

### 逐项比对结果

| 检查项 | 默认路径（`BlockHarvestActionExecutor.execute`） | 缓存路径（`ChunkCachedHarvester.harvestNext`） | 是否等价 |
|---|---|---|---|
| 空气/空值判定 | `world.getBlock` | `currentEbs.getBlockByExtId` | ✅ |
| `isUnbreakable`（硬度 < 0） | 有 | 有 | ✅ |
| TE / GT 携带 TE 分流到原版 | 有 | 有 | ✅ |
| `BreakEvent`（`Config.fireBreakEvent`） | 有 | 有 | ✅ |
| TiC `beforeBlockBreak` 钩子 | 有 | 有 | ✅ |
| 剪刀 `IShearable` 钩子 | 有 | 有 | ✅ |
| GT5 工具耐久预检 | 有 | 有 | ✅ |
| 工具损耗 / 销毁 | 有 | 有 | ✅ |
| `onBlockHarvested` / `onBlockDestroyedByPlayer` | 有 | 有 | ✅ |
| 掉落（含 Natura 特判） | 有 | 有 | ✅ |
| XP | 有 | 有 | ✅ |
| 树叶衰减标记 | 有 | 有 | ✅ |
| 邻居通知 sink | 有 | 有 | ✅ |
| **元数据清零** | 由 `Chunk.func_150807_a` 隐式完成 | `writeAirToEbs` 显式 `setExtBlockMetadata(0)` | ✅ 效果等价 |
| **高度图维护** | 由 `Chunk.func_150807_a` 自动完成 | `updateHeightMap` + `markChunkModified` 手工补偿 | ✅ 效果等价 |
| **世界写入层** | `World.setBlock(x, y, z, air, 0, 2)` | 直写 `ExtendedBlockStorage.func_150818_a` | ❌ **不同** |

### 结论

**方块级别的安全检查项完全等价**；唯一的实质差异是**世界写入层**：

- 默认路径经过 `World.setBlock` → `Chunk.func_150807_a`；
- 缓存路径直接写 `ExtendedBlockStorage` 数组，绕开整个 `World.setBlock` / `Chunk.func_150807_a` 层。

因此「skips some safety checks」最可能的含义是：**任何挂在 `World.setBlock` / `Chunk.func_150807_a`
这一层的第三方逻辑都看不到这次方块变更**——典型如：

- 领地 / 地形保护类模组（常 mixin `World.setBlock` 做权限校验）；
- 方块日志 / 回滚类模组（记录谁在何时改了什么）；
- 反作弊 / 服务器审计插件；
- 其它模组对 `World.setBlock` 的通用 mixin。

### 严谨性声明

**无法从本仓库代码判定原作者的原始意图**——上述是代码结构推断，不是从注释或提交历史中读出的
事实。要确证需要查阅引入该开关的提交信息或其作者。本文档只陈述可验证的部分：

1. 方块级检查项等价（上表逐项可比对）；
2. 写入层确实不同（`World.setBlock` vs 直写 EBS）；
3. 因此「跳过安全检查」在**写入层**这一表述上是成立的，在**方块检查项**这一表述上不成立。

**处置**：默认值保持 `false`，在本批中不做行为变更。若未来要开启，建议先确认目标整合包是否使用
领地保护/日志类模组，并做 A/B 实测。

---

## 附：本文档结论的验证命令

```bash
# 1.1 默认值确认（仍应为 false）
grep -n "useChunkCachedHarvest" src/main/java/com/czqwq/EZMiner/Config.java

# 2.1 死方法确认（已完成，均应无输出）
grep -rn "\.executeBatch(" src/main/java --include=*.java | grep BlockHarvestActionExecutor
grep -rn "executeWithPreResolved\|groupByChunk\|ChunkBatch" src/main/java --include=*.java

# 4.1 单一真源确认（实现应只在 utils/PosEncoding.java）
grep -rn "encodePos\|PosEncoding\.encode" src/main src/test --include=*.java

# 4.1 变异测试（改坏移位后 test 应失败，改回后应通过）
#   sed -i 's/<< 38/<< 37/' src/main/java/com/czqwq/EZMiner/utils/PosEncoding.java
#   ./gradlew test

# 2.2 脚手架零调用方确认（应无输出）
grep -rn "chainPlanningRuntimeFactory\." src/main/java --include=*.java
grep -rn "\.get(ChainMode\|\.all()" src/main/java --include=*.java

# 4.1 测试存在性
find src/test -name "*.java"
grep -n "testImplementation" dependencies.gradle
```
