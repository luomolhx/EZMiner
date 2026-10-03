# EZMiner 代码库分析

- **分析对象**：`master` @ `8641428`（fix breaking logic / prevent end-portal breaking）
- **分析日期**：2026-10-03
- **分析方式**：静态阅读（架构分层 → 数据流 → 线程契约 → 死代码/重复实现扫描 → 耦合度量化）
- **未做**：未运行游戏内手测，未执行性能 profiling

> **更新记录（2026-10）**：本文档中标记为 ✅ 的条目已在第一批优化中修复，详见
> [`optimization-directions.md`](./optimization-directions.md) 与 `AGENTS.md` 的
> "Recent changes (2026-10)"。未标记的条目仍然有效。

---

## 一、项目定位与规模

| 项 | 值 |
|---|---|
| 类型 | Minecraft Forge **1.7.10** 连锁挖掘模组（面向 GTNH 整合包） |
| 代码量 | 184 个 Java 文件，主代码 ~24.5k 行 |
| 最大文件 | `client/gui/EZMinerConfigGui.java` 2206 行 / 113 KB |
| 构建 | GTNH convention 插件 + Jabel（Java 17 语法 → JVM 8）+ Mixin |
| 验证方式 | `./gradlew spotlessApply build`（Spotless + Checkstyle + reobf + jar） |
| 测试 | 3 个 JUnit 文件，**未接入 build / CI** |

### 各包行数

| 包 | 行数 | 职责 |
|---|---:|---|
| `client/` | 5794 | GUI（配置界面、HUD、库存按钮）、预览渲染、按键输入、智能换工具 |
| `chain/` | 5278 | 连锁子系统：state / lifecycle / planning / execution / mode / network / watchdog |
| `core/` | 3939 | 遗留层：Manager、BaseOperator、`founder/` 位置搜索 |
| `compat/` | 1955 | GT5、TiC、Natura、Witchery、Bandit、VisualProspecting、EndlessIDs |
| `Config.java` | 1913 | 全部配置项（112 个 public static 字段） |
| `network/` | 1600 | 主网络包与配置同步 |
| `utils/` | 999 | 表达式过滤、时间格式、工具可用性判定 |
| `mixin/` | 581 | 5 个 Mixin（3 个 early + 2 个 GT 矿适配） |
| `toolswap/` | 301 | 工具借用账本 |
| `command/` | 301 | 重载配置命令 |
| `thread/` | 279 | Pauseable / ParallelTick / SearchWorkerPool |
| `api/` | 204 | `EZMinerAPI` 对外接口 |

---

## 二、架构分层与数据流

```
玩家挖第一个方块
  └─ Manager.onBlockBreak (core/Manager.java:125)   ← 每玩家一实例，事件驱动
       └─ BaseOperator (core/BaseOperator.java:110) ← ServerTickEvent，每 tick 最多 64 格
            ├─ 规划 planningTask ──┬─ LegacyFounderPlanningTask → core/founder/（后台线程 BFS / 壳扫描）
            │                      └─ CachedPositionsPlanningTask（预计算缓存喂队列）
            └─ 执行 canBreakPositions 队列 → 3 条世界修改路径
```

### 三条世界修改路径（全部服务端线程）

| # | 路径 | 入口 |
|---|---|---|
| 1 | **单块快路径** | `MixinItemInWorldManager.ezminer$tryHarvestBlockFast` → `World.setBlock(..., air, 0, 2)`（flag 2 = 仅客户端更新，不发邻居通知） |
| 2 | **批量 chunk 缓存路径（活路径）** | `ChunkCachedHarvester.harvestNext` → `ChunkBlockWriteHelper.writeAirToEbs`（直写 `ExtendedBlockStorage.func_150818_a`，EndlessIDs 安全，元数据强制清零） |
| 3 | **TE 路径** | 原版 `ItemInWorldManager.tryHarvestBlock`（保证 `breakBlock` / `removeTileEntity` 执行） |

### 搜索 / 规划子系统

- `Pauseable`（`thread/Pauseable.java`）—— 协作式 tick 边界暂停；`consumeBudget()` 在 leader 线程逐位置检查，worker 线程只读 `paused` 标志。
- `SearchWorkerPool` —— 共享工作线程池，跨 founder 复用。
- `BasePositionFounder` —— 壳扩展扫描；`StripScan` 游标使单层壳可跨 tick 恢复（修复"条纹残留"bug，`core/founder/BasePositionFounder.java:256`）。
- `ChainPositionFounder` —— 单线程 BFS，`ArrayDeque` 双端 frontier + 原子 visited 准入。
- `ChainPreCalcEngine` —— 缓存连锁模式的预计算 BFS，**跑在服务端线程内**（规避 Hodgepodge 跨线程告警）。

### 线程契约

```
netty IO 线程 ──SimpleNetworkWrapper──▶ 服务端包处理器
      │
      └─ MainThreadEnforcer.guardedNull()  (network/MainThreadEnforcer.java:52)
           └─ 延迟入队 DEFERRED
                └─ PlayerManager.onServerTick → drainDeferred()  (上限 1024/tick)
```

- `PlayerManager.managers` 为 `ConcurrentHashMap`（IO 线程读、服务端线程增删）。
- 所有世界写入/邻居通知仅在服务端线程执行；后台 founder 线程只做只读扫描。

---

## 三、值得肯定的设计

1. **注释解释"为什么"，而非"是什么"**。几乎每处兼容性 hack 都写明了触发场景与失败模式，例如：
   - `ChunkBlockWriteHelper.writeAirToEbs` 说明元数据清零是为修复 GT 矿 `name.0`；
   - `DeterminingIdentical.computeOreMetaMask` 记录了 Galacticraft `blockMoon` 的混元数据案例；
   - `Manager.onHarvestDrops` 记录了 Bandit 掉落收集的冲突原因。
   这类注释在模组代码中罕见，显著降低了后续维护成本。

2. **兼容层隔离干净**。`compat/` 全部通过 `Class.forName` + 缓存反射对象探测，无编译期硬依赖；`OreCompatRegistry` 把矿判定收敛为注册表，新增矿类模组只需加一个 adapter。

3. **性能优化针对热点且有度量意识**：
   - visited 集用 long 编码（`BasePositionFounder.encodePos`，x/z 偏置 30M 占 26 位、y 占 12 位，无碰撞）；
   - blast worker 直接读 `paused` 标志，跳过 `Thread.currentThread()` + `isInterrupted()` 的原生调用；
   - A1 预取 block/meta 传入 `DeterminingIdentical.identical`，避免重复世界查询；
   - 客户端预览 `SpaceCalculator` 用复用的 `boolean[12]` 边掩码替代每块 `HashSet`。

4. **策略单一真源**。`DeterminingIdentical.isUnbreakable`（`core/founder/DeterminingIdentical.java:353`）是"不可破坏方块"的唯一判定入口，注释明确要求调用点只询问、不重复推导——这正是 `commit 8641428` 修复末地传送门被破坏的基础。

---

## 四、发现的问题

### P1 — 死代码与重复实现（约 500 行）✅ 已删除（2026-10）

| 位置 | 行数 | 情况 |
|---|---:|---|
| `chain/mode/`（7 文件） | ~200 | `ChainModeBootstrap` 把定义注册进 registry，但 `.get()` / `.all()` **零调用方** —— 纯脚手架 |
| `chain/planning/ChainPlanner.java` + `ChainPlanningStrategy` / `ChainBlockMatcher` / `ChainCandidateFilter` / `ChainTraverser` | ~70 | `ChainPlanningRuntimeFactory.create()` **零调用方**，接口无实现类 |
| `BlockHarvestActionExecutor.executeBatch()`（:117-243）+ `executeWithPreResolved()`（:250-268） | ~150 | **零调用方** |

**最危险的一条**：`BlockHarvestActionExecutor.executeBatch` 与 `ChunkCachedHarvester.harvestNext`（`chain/execution/ChunkCachedHarvester.java:95-229`）是近乎逐行的双胞胎，重复了：

- TiC `ActiveToolMod.beforeBlockBreak` 钩子与位置预注册
- 剪刀（`ShearsHarvestBridge.fireIfShears`）钩子
- GT5 工具耐久预检
- 工具损耗、`onBlockHarvested`、EBS 写空、树叶衰减标记
- 掉落（含 Natura 仙人掌特判）、XP 计算

近期修复（`isUnbreakable`、`isGTTileEntityCarrier`、`flagNeighbouringLeavesForDecay`）都必须在**两处同步修改**才生效。一旦漏改一处，修复会静默失效——这是当前代码库最高的维护风险点。

> 注：`AGENTS.md` 与 `docs/review-summary.md` 均已记录 `chain/mode` 与 planner 脚手架为 write-only，但标注为"documented, not yet deleted"。`executeBatch` 的重复未见任何文档记录。

### P1 — `onWorldUnload` 过度清理 ✅ 已修复（2026-10）

`chain/lifecycle/ChainLifecycleService.java:45`：

```java
public void onWorldUnload(Map<UUID, Manager> managers) {
    EZMiner.chainStateService.onWorldUnload();
    for (Manager mgr : managers.values()) {
        stopRuntime(mgr);   // 无维度判断
    }
}
```

不检查是**哪个** world 卸载。任意 `WorldEvent.Unload`（非主世界维度变空后卸载、服务器关闭）会：

1. 停止**所有**玩家的进行中连锁；
2. 经 `stopRuntime → mgr.clearDrops()` **丢弃已收集但尚未生成的掉落物**。

概率低，但构成一条真实的物品损失路径。建议：加 `event.world.provider.dimensionId` 过滤，并把 `clearDrops()` 改为先 `flushDrops()`。

### P2 — 结构性债务（`docs/todo.md` 已记录，尚未动工）

| 问题 | 现状 |
|---|---|
| `EZMinerConfigGui` 数据驱动化 | 2206 行 / 113 KB；行→字段映射重复在 `getRowLabelKey` / `initGui` / `drawTab` / `updateScrolledPositions` / `mouseClicked` 5 处；加 1 个配置项需改 10+ 处 |
| `Config.java` God Object | 1913 行、112 个 public static 字段；服务端 / 客户端 / runtime 覆盖 / I-O / sync / clamp 混放 |
| `core/` ↔ `chain/` 双向耦合 | chain→core **37** 处 import，core→chain **31** 处；`chain.planning` 直接 import 12 个 `core.founder` 类。当前"解耦"是目录分层，不是依赖倒置 |
| `Manager` 职责膨胀 | 一个类承担事件订阅、连锁启动、掉落收集（4 级回退链）、配置同步、预计算、特殊模式 tick 六件事 |

### P3 — 零散观察（其中「文档与事实不符」一项 ✅ 已修正 2026-10）

- **配置同步缺口**：`docs/review-summary.md` 自述有 **19 个旧服务端字段未被 `PacketServerConfig` 同步**，专用服上 OP 保存 GUI 会静默重置；根因是 `PacketSaveServerConfig` 的 30 参构造函数。
- **每 tick 分配**：`BaseOperator.processBatchChunkCached` 每 tick `new ChunkCachedHarvester()`（内含 `boolean[256]`），可提升为 operator 字段复用。
- **文档与事实不符**：`AGENTS.md` 的 Build & Validate 章节称 "**No tests exist**"，但 `src/test/java/com/czqwq/EZMiner/core/founder/` 下已有 3 个 JUnit 测试（`ShellGeometryTest`、`EncodePosTest`、`BfsConsistencyTest`），且 `dependencies.gradle` 已声明 `testImplementation('junit:junit:4.13.2')`。这 3 个测试是纯逻辑测试（不依赖 Minecraft 类），却未接入 `build` 任务或 CI workflow。

---

## 五、建议优先级

| 优先级 | 事项 | 预估成本 | 收益 |
|---|---|---|---|
| 1 | 删除死代码（`chain/mode/`、planner 脚手架）+ 合并重复执行器 | 半天 | 直接消除双份维护风险 |
| 2 | 把已有 3 个 JUnit 测试接入 `build` / CI | 极小 | 形成最小回归网，防止壳几何 / `encodePos` 回归 |
| 3 | 修 `onWorldUnload` 维度过滤 + 掉落 flush | 小 | 修一条真实物品损失路径 |
| 4 | 按 `docs/todo.md` 推进 GUI 数据驱动化与 `Config` 拆分 | 大（可增量） | 新增配置项成本从 10+ 处降到 1 处 |

---

## 六、附：验证清单

本文档中所有结论的验证方式：

```bash
# 死代码确认（零调用方）
grep -rn "chainPlanningRuntimeFactory\." --include=*.java src/main/java
grep -rn "\.all()\|\.get(ChainMode" --include=*.java src/main/java
grep -rn "executeBatch\|executeWithPreResolved" --include=*.java src/main/java/com/czqwq/EZMiner/chain/execution/BlockHarvestActionExecutor.java

# 耦合度量化
grep -rn "import com.czqwq.EZMiner.core" src/main/java/com/czqwq/EZMiner/chain/ | wc -l   # 37
grep -rn "import com.czqwq.EZMiner.chain" src/main/java/com/czqwq/EZMiner/core/ | wc -l   # 31

# 测试存在性
find src/test -name "*.java"
```
