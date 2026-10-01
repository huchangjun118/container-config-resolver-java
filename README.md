# Container Config Resolver (Java)

在**本地进程内**把来自多个来源的容器配置按确定优先级合成一份自洽的运行定义，
完成入口点/命令合并、变量插值、兼容性校验，并支持并发原子重载与失败回退。
**不依赖容器运行时或任何外部服务**；所有结论在启动前给出、可解释、可追溯。

---

## 1. 分层来源与优先级

来源自低到高固定为五级；读取结果永远是某次**整体提交的完整版本**
（`ConfigSourceVersion`），不存在跨版本的字段级混合：

| 优先级 | 层级 (`SourceLayer`) | 含义 |
|---|---|---|
| 0 | `IMAGE` | 镜像自带：image/os/arch、镜像 ENTRYPOINT、镜像 CMD |
| 1 | `TEMPLATE` | 基础模板 |
| 2 | `ENVIRONMENT` | 环境覆盖（dev/prod…） |
| 3 | `SERVICE` | 服务级覆盖 |
| 4 | `RUNTIME` | 运行时注入（最高优先级） |

### 字段级合并 vs 整体替换

| 字段 | 语义 |
|---|---|
| `image` / `platform` / `workingDir` / `user` / `privileged` / `readOnlyRootfs` | **标量整体替换**：取最高优先级来源的值 |
| `env` / `labels` | **字段级（按 key）合并**：高优先级只覆盖同名 key，其余继承 |
| `ports` / `capabilities` | **列表追加 + 内容去重**（端口按 `ip:hostPort/proto->containerPort`，能力按名字） |
| `mounts` | 追加；**相同容器挂载点（target）由高优先级整条覆盖** |
| `entrypoint` / `command` | **不可分割的整体替换**，并叠加 Docker CMD 重置语义（见下） |

每个来源字段用 `Presence<T>` 表示：

- 键**缺失** → `absent()`：合并时跳过，不影响下层；
- 键**存在** → `present(v)`：即使值是 `null` 或空列表/空 map 也是显式提交。
  例如 `entrypoint: []` 表示"显式清空入口点"，`env: null` 表示把整个 env 重置为空。

JSON 反序列化由 `PresenceDeserializer` 保证这一区分。

---

## 2. 入口点与命令的合并语义（对齐 Docker）

设镜像自带 `ENTRYPOINT_img` 与 `CMD_img`：

1. 没有任何上层声明 → 最终为 `ENTRYPOINT_img` + `CMD_img`。
2. 上层只覆盖 `command`（cmd）→ `ENTRYPOINT_img` 照常继承，cmd 被整体替换。
3. **任何一层显式声明 `entrypoint`（包括空列表）→ 镜像 CMD 立即重置失效**；
   最终命令行为 `新ENTRYPOINT + 新CMD`，其中新 CMD 只可能来自
   "声明该 entrypoint 的同一层"或"更高层显式声明的 command"。
4. 同一层同时声明 entrypoint 与 command → 两者都取该层值。
5. 显式 `entrypoint: []` 可清空继承来的入口点。

最终结果的 `origins` 中：

- `entrypoint` / `command` 记录取值来源层级与名称；
- 当 CMD 因 entrypoint 被覆盖而重置时，`command` 的追溯条目带 `detail`
  说明是被哪一层重置的。

---

## 3. 变量插值与转义

插值在合并完成后进行（先合并、后插值），作用于 `env`、`labels`、
`entrypoint[]`、`command[]`、`image`、`platform`、`workingDir`、`user`。

| 写法 | 含义 |
|---|---|
| `${NAME}` / `$NAME` | 引用**显式注入的外部变量**（reload 时传入的 `externals`） |
| `${env.KEY}` | 引用最终 env 中的其他条目（支持链式引用） |
| `${field.image}` / `${field.platform}` / `${field.workingDir}` / `${field.user}` | 引用合并后的其他标量字段 |
| `$$` | 转义为字面量 `$` |
| `$${...}` | 转义为字面量 `${...}`，**不展开** |

异常引用会得到**三类可区分错误**（均不会静默替换为空值或原样输出）：

- `UndefinedReferenceException`：引用未定义变量/不存在的 env key/不支持的字段；
- `CircularReferenceException`：env 之间直接或间接循环（携带完整引用链）；
- `MalformedReferenceException`：语法非法（未闭合 `${`、空 `${}`、结尾孤立 `$` 等）。

插值只使用显式传入的 `externals`，**不回退进程环境变量**，从而保证跨进程确定性。

---

## 4. 兼容性校验（启动前拒绝不可运行的定义）

校验由 `ConfigValidator` 基于 `HostConstraints`（主机事实：os/支持的架构/
已占用端口/是否允许特权/是否允许可写 /proc、/sys）执行。
存在任何 `ERROR` 即判定 `valid=false`，该版本**不会提交**：

| code | 级别 | 触发条件 |
|---|---|---|
| `PLATFORM_UNSUPPORTED` | ERROR | 镜像 os 与主机不一致；镜像架构主机不支持；请求 platform 的架构主机不支持 |
| `PLATFORM_MISMATCH` | ERROR | 显式 `platform` 与镜像 os/arch 不一致 |
| `PORT_CONFLICT` | ERROR | 配置内两条绑定使用同一主机端点（ip+hostPort+protocol） |
| `PORT_IN_USE` | ERROR | 主机端点已被其他进程占用 |
| `MOUNT_TARGET_CONFLICT` | ERROR | 同一容器路径被重复挂载 |
| `MOUNT_PROTECTED` | ERROR | 默认可写挂载 `/proc`、`/sys`（主机策略可放开） |
| `PRIVILEGED_FORBIDDEN` | ERROR | 主机平台策略禁止特权容器 |
| `PRIVILEGED_WITH_READONLY_ROOTFS` | WARN | 特权 + 只读根文件系统意图可能冲突 |
| `NO_WRITABLE_PATH` | WARN | 只读根文件系统下没有任何可写挂载（如 /tmp） |
| `NO_ENTRYPOINT` | WARN | entrypoint 与 command 同时为空 |

同端口不同协议（tcp/udp）视为不同端点，允许共存。
校验结论 `ValidationResult{valid, issues[]}` 随快照一起保留可查询。

---

## 5. 并发重载与读取一致性

`ConfigResolver` 编排 **合并 → 插值 → 校验 → 原子发布**：

- 已提交快照保存在单个 `volatile` 引用 `CommittedSnapshot` 中，快照本身不可变；
- 重载在**局部变量**上完成全部计算，只有成功才替换引用；
- 读取路径（`current()`）**无锁**：永远拿到某个完整自洽版本，
  慢读取不阻塞重载（见 `ConcurrentReloadTest.slowReaderDoesNotBlockReload`）；
- 重载方法 `synchronized`：并发重载串行化，避免版本乱序生效。

## 6. 失败隔离与回退

- 来源读取失败（`SOURCE`）、合并失败（`MERGE`）、插值失败（`INTERPOLATE`）、
  校验失败（`VALIDATE`）都只记录到 `lastFailure`，**旧快照保持不变且持续可读**；
- 连续失败不会让服务不可用；输入恢复合法后可立即再次发布；
- 成功发布后 `lastFailure` 清空。失败记录含 revision、阶段、原因与字段路径。

## 7. 确定性与可追溯

- 相同输入 ⇒ 逐字段一致的输出：env/labels 以排序键序存储，
  版本指纹 `VersionHasher`（SHA-256，固定字段顺序的规范序列化）
  **跨进程/重启不变**，且只取决于最终字段值（与 revision、来源命名无关）；
- 每个最终字段都在 `resolved.origins` 中有一条 `FieldOrigin`：
  `path`（如 `env.LOG_LEVEL`、`entrypoint[0]`、`mounts[2].target`）、
  `layer`（来源层级）、`source`（模板/环境/服务名，镜像层为镜像引用）、
  `detail`（如 CMD 重置说明）。

---

## 8. HTTP 接口（配置项）

Spring Boot 装配见 `web/ResolverConfig`，默认主机约束为 linux/amd64、
无占用端口、允许特权、禁止可写 /proc、/sys；可用自定义 `HostConstraints` bean 覆盖。

| 方法与路径 | 说明 |
|---|---|
| `GET /resolver/config` | 当前已提交快照（最终定义 + 字段追溯 + 校验结论）；未提交返回 404 |
| `GET /resolver/failure` | 最近一次失败原因（200）；无失败返回 204 |
| `POST /resolver/reload` | 提交一个完整来源版本并求值；成功 200，失败 **422** 且不影响已生效版本 |

`POST /resolver/reload` 请求体：

```json
{
  "revision": "r1",
  "image": {
    "image": "nginx:1.27", "os": "linux", "architecture": "amd64",
    "entrypoint": ["/docker-entrypoint.sh"],
    "command": ["nginx", "-g", "daemon off;"]
  },
  "layers": [
    {"layer": "TEMPLATE",    "name": "base", "config": {"workingDir": "/srv"}},
    {"layer": "ENVIRONMENT", "name": "prod", "config": {"env": {"LOG_LEVEL": "info"}}},
    {"layer": "SERVICE",     "name": "web",  "config": {
        "user": "nginx",
        "ports": [{"hostIp": "0.0.0.0", "hostPort": 8080, "containerPort": 80, "protocol": "tcp"}],
        "mounts": [{"source": "/host/data", "target": "/data", "readOnly": false, "type": "bind"}]
    }},
    {"layer": "RUNTIME",     "name": "inject", "config": {"privileged": false}}
  ],
  "externals": {"LOG_LEVEL": "debug"}
}
```

### 支持的原始配置项（`RawContainerConfig`）

`image`、`platform`、`entrypoint[]`、`command[]`、`workingDir`、`user`、
`env{}`、`labels{}`、`ports[]{hostIp,hostPort,containerPort,protocol}`、
`mounts[]{source,target,readOnly,type}`、`capabilities[]`、
`privileged`、`readOnlyRootfs`。

### 编程方式使用

```java
ConfigResolver resolver = new ConfigResolver(
        new ConfigMerger(),
        new VariableInterpolator(),
        new ConfigValidator(HostConstraints.linuxAmd64(Set.of(9090))));

ConfigSourceVersion v = new ConfigSourceVersion("r1", imageMetadata,
        List.of(new ConfigSourceVersion.LayeredSource(
                SourceLayer.SERVICE, "web", rawConfig)));

Optional<CommittedSnapshot> snap = resolver.reload(v, Map.of("LOG_LEVEL", "debug"));
if (snap.isEmpty()) {
    ResolveFailure f = resolver.lastFailure().orElseThrow(); // 阶段+原因+字段路径
}
```

---

## 9. 构建与测试

```bash
mvn test          # 离线: mvn -o test
mvn spring-boot:run
```

测试覆盖（共 59 个用例）：

- `ConfigMergerTest`：标量优先级、env 字段级合并、列表追加去重、
  挂载点覆盖、entrypoint/command 的 Docker 合并与重置语义、显式空列表；
- `VariableInterpolatorTest`：外部/env/字段引用、裸 `$VAR`、`$$`/`$${}` 转义、
  未定义引用、直接/间接循环、未闭合/空表达式等语法错误；
- `ConfigValidatorTest`：平台/架构、端口冲突与占用、挂载冲突与 /proc、/sys 保护、
  特权策略、特权+只读组合、缺失入口点；
- `ConfigResolverTest`：成功发布、校验/插值/来源读取失败隔离与回退、
  连续失败可用性、相同定义跨实例指纹一致、字段级来源追溯；
- `ConcurrentReloadTest`：并发重载下读取永远自洽（无字段级混合）、
  慢读取不阻塞重载、失败版本永不可见；
- `RawConfigJsonTest`：键缺失 / 显式 null / 空列表的区分；
- `ConfigControllerIntegrationTest`：REST 端到端（200/422/404/204、旧版本保留）。
