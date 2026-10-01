# Container Config Resolver（容器运行配置求值与校验）

在本地进程内把多个来源的容器配置按确定的优先级合成一份自洽的运行定义，并在启动前给出可解释的校验结论。不依赖容器运行时或任何外部服务。

## 1. 分层来源与优先级

| 优先级 | 来源 | 权重 | 说明 |
| --- | --- | --- | --- |
| 1（最低） | 基础模板 `BASE_TEMPLATE` | 10 | 镜像自带默认值（镜像、入口点、命令等） |
| 2 | 环境覆盖 `ENVIRONMENT` | 20 | 环境级差异化配置 |
| 3 | 服务级覆盖 `SERVICE` | 30 | 单个服务的个性化配置 |
| 4（最高） | 运行时注入 `RUNTIME_INJECTION` | 40 | 运行时刻注入的临时配置 |

合并顺序固定为从低优先级到高优先级；同优先级按来源 id 排序，保证确定性。读取结果只能是某个**已提交的完整版本**（不可变快照），不存在中间态。

## 2. 合并语义

| 字段类型 | 字段 | 语义 |
| --- | --- | --- |
| 标量 | `image`、`platform`、`arch`、`workingDir`、`user`、`privileged`、`readOnlyRootFilesystem` | 高优先级非空值**整体替换**低优先级值 |
| 映射 | `env`、`labels` | **字段级合并**：低优先级的键保留，高优先级的同键覆盖 |
| 列表 | `entrypoint`、`command`、`ports`、`volumes` | 高优先级非空列表**整体替换**；显式空列表 `[]` 表示**重置** |

未指定的字段（`null`）不参与合并；显式空集合与 `null` 语义不同（前者重置，后者继承）。

### 入口点与命令（entrypoint / command）

入口点与命令相互独立，各自遵循列表整体替换语义，符合容器运行时的既有语义（Docker 矩阵）：

- 镜像自带 `entrypoint=E`、`command=C`，无覆盖 → 最终 `E + C`；
- 仅覆盖入口点 `E'` → `E' + C`（命令继承）；
- 仅覆盖命令 `C'` → `E + C'`（命令重置）；
- 同时覆盖 → `E' + C'`；
- 显式空命令 `[]` → 命令重置为空；入口点与命令**不得同时为空**（校验拒绝）。

每个最终字段都记录来源（`sourceId` + 字段路径），可通过 `/config/current` 的 `provenance` 追溯。

## 3. 变量插值与转义

合并完成后对所有字符串字段做插值：

| 语法 | 含义 | 示例 |
| --- | --- | --- |
| `${field.path}` | 引用本配置字段 | `${image}`、`${env.KEY}`、`${labels.app}` |
| `${env:NAME}` | 引用外部取值（环境变量） | `${env:REGISTRY}` |
| `$$` | 转义，输出字面量 `$` | `$${HOME}` → `${HOME}`；`$$HOME` → `$HOME` |

异常引用给出**可区分**的错误类型，绝不静默替换为空值或原样输出：

| 异常 | 触发场景 |
| --- | --- |
| `UndefinedReferenceException` | 引用的字段或外部取值不存在 |
| `CircularReferenceException` | 字段引用链形成环（含自引用） |
| `ReferenceTypeException` | 标量位置引用列表字段（如 `${entrypoint}`） |

任一引用异常都会导致整次求值失败，不会产出半成品。

## 4. 兼容性校验

校验器对最终配置做组合校验，任一冲突都拒绝并给出原因（可多条同时报告）：

1. **平台**：必须为受支持平台（`linux/amd64`、`linux/arm64`），格式为 `os/arch`；
2. **架构**：若指定 `arch`，必须与 `platform` 的架构分量一致（如 `linux/amd64` + `arm64` 冲突）；
3. **端口**：端口必须在 1-65535；宿主机端口不得重复占用；
4. **卷挂载**：挂载目标必须为绝对路径；容器内挂载点不得重复；
5. **特权与只读**：`privileged=true` 与 `readOnlyRootFilesystem=true` 互斥；
6. **可启动性**：入口点与命令不得同时为空。

## 5. 并发重载与失败隔离

- 读取通过 `AtomicReference` 返回当前已提交的**不可变快照**，任一时刻读到的都是自洽的单个版本，不会出现字段级混合；
- 重载采用**写时复制**：在独立栈上完成 合并 → 插值 → 校验，通过后才原子替换引用；慢读取持有旧快照，不阻塞重载，重载也不影响读取；
- 某次重载失败时，上一版本继续生效，失败原因、尝试版本号、连续失败次数可查询；连续失败不会让服务不可用；
- 重载成功后连续失败计数清零、失败记录清除；版本号单调递增，失败尝试占用版本号但不提交。

## 6. 确定性与可追溯

- 相同输入逐字段一致：来源按固定顺序合并，插值递归解析（环检测），跨进程重启结果不变；
- 每个最终字段都可追溯到来源与路径（`provenance`），映射字段精确到键（如 `env.LOG_LEVEL`）。

## 7. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/config/current` | 当前已提交版本（版本号、来源、配置、字段追溯） |
| POST | `/config/reload` | 触发一次重载，返回成功/失败与原因 |
| GET | `/config/failures` | 当前版本号与最近一次失败记录 |

服务启动时会自动触发一次重载；失败不影响启动。

## 8. 配置项（ContainerConfig 字段）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `image` | String | 镜像引用 |
| `platform` | String | 平台，`os/arch` |
| `arch` | String | 架构（与 platform 的 arch 分量一致） |
| `entrypoint` | List&lt;String&gt; | 入口点 |
| `command` | List&lt;String&gt; | 命令（参数） |
| `env` | Map&lt;String,String&gt; | 环境变量（字段级合并，支持插值） |
| `labels` | Map&lt;String,String&gt; | 标签（字段级合并，支持插值） |
| `ports` | List&lt;PortMapping&gt; | 端口映射（hostPort/containerPort/protocol） |
| `volumes` | List&lt;VolumeMount&gt; | 卷挂载（source/target/readOnly） |
| `privileged` | Boolean | 特权模式 |
| `readOnlyRootFilesystem` | Boolean | 只读根文件系统 |
| `workingDir` | String | 工作目录 |
| `user` | String | 运行用户 |

## 9. 测试

```bash
mvn test
```

覆盖：合并优先级与语义、入口点/命令继承与重置、插值异常（未定义/循环/类型）、兼容性冲突、并发重载一致性、慢读取不阻塞、失败隔离与回退、确定性、HTTP 接口。
