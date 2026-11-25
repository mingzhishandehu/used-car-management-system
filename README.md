# 二手车业务管理系统

全栈项目，目标：**能跑、能演示、面试能讲透**。不做生产级运维（不做多租户、不做 K8s 编排）。

## 技术栈

| 层 | 选型 | 用途 |
|---|---|---|
| 后端 | Spring Boot 3.3.5 / JDK 21 | Web 框架 |
| ORM | MyBatis-Plus 3.5.9 | 单表 CRUD、分页、逻辑删除 |
| 数据库 | MySQL 8.4 | 16 张表（含 AI 会话 2 张、审批单 1 张、流转记录 1 张、Agent 轨迹 1 张） |
| 大模型 | 硅基流动 `deepseek-ai/DeepSeek-V4.1-Flash` | 自然语言收车，只抽参数不写业务表 |
| 鉴权 | Sa-Token 1.39 | 登录态、角色校验 |
| 文档 | springdoc-openapi 2.6 | Swagger UI 在线调接口 |
| 前端 | Vue 3 + Vite + Arco Design（阶段五） | 管理后台 |
| 图表 | ECharts（阶段五） | 库存与资金占用仪表盘 |

## 目录结构

| 路径 | 作用 |
|---|---|
| `sql/V1__init_schema.sql` | 11 张表的建表语句 + `pricing_rule` 初始参数 |
| `sql/V7__ai_intake.sql` | AI 收车会话与消息两张表，聊天记录长期保存 |
| `sql/V8__employee_module.sql` | 给 vehicle 补 `created_by` 归属列（不加表） |
| `sql/V9__vehicle_approval.sql` | 新建 `vehicle_approval` 申请表 + `vehicle.approved_by` 列 |
| `sql/V10__employee_handover.sql` | 新建 `vehicle_handover` 流转表 + `sys_user.employment_status` 列 |
| `sql/V11__approval_conversation.sql` | 给申请单加 `source_conversation_id`，批准后回写 AI 会话卡片（只加列不加表） |
| `backend/src/main/java/com/usedcar/agent/` | AI 收车：抽取器、写库工具、只读工具注册表、通用只读 SQL 校验器、意图路由、会话编排 |
| `backend/.../vehicle/service/VehicleHandoverService.java` | 离职时的批量对接权流转与留痕 |
| `backend/src/main/java/com/usedcar/system/EmployeeService.java` | 员工账号管理与 ADMIN 权限收口 |
| `backend/src/main/java/com/usedcar/approval/` | 审批闸门、申请单、批准执行分派、字段规格 |
| `backend/src/main/java/com/usedcar/order/` | 成交订单、售后记录、清库前售后闭环校验 |
| `backend/pom.xml` | Maven 依赖，锁定 Boot 3.3.5 / JDK 21 |
| `backend/src/main/java/com/usedcar/UsedCarApplication.java` | 启动类，开 `@EnableScheduling` |
| `backend/src/main/java/com/usedcar/common/` | 统一响应 `ApiResult`、业务异常、全局异常处理 |
| `backend/src/main/java/com/usedcar/config/` | MyBatis-Plus 分页插件、Sa-Token 登录拦截 |
| `backend/src/main/java/com/usedcar/system/` | 健康检查，验证库连没连上、表建没建好 |
| `backend/src/main/java/com/usedcar/vehicle/` | 车辆模块：状态机枚举、实体、Mapper、查询接口 |
| `backend/src/main/resources/application.yml` | 数据源、MyBatis-Plus、Sa-Token、Swagger 配置 |
| `frontend/` | 阶段五创建 |

## 启动步骤

1. 建库建表（已执行过，无需重复）

```bash
"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p < sql/V1__init_schema.sql
```

2. 配置数据库口令与 AI 密钥（都不进仓库）

口令不写在代码里，通过环境变量 `USEDCAR_DB_PASSWORD` 注入；AI 收车另需硅基流动的 `USEDCAR_AI_API_KEY`。

```bash
# 首次：复制模板
cp .env.example .env

# 编辑 .env，把占位符换成你本机的真实口令
# 每次开新终端先加载
set -a; source .env; set +a
```

`.env` 已在 `.gitignore` 里，不会进仓库。用 IntelliJ 跑的话，在 Run Configuration 的
Environment variables 里加一条 `USEDCAR_DB_PASSWORD=你的口令` 即可。

AI 收车的变量见 `.env.example`。没配 `USEDCAR_AI_API_KEY` 时，发消息会明确返回「未配置 AI 密钥」，
不会静默假装成功；只想联调前端和写库链路时把 `USEDCAR_AI_PROVIDER` 设为 `mock`，
抽取器改用本地关键词解析，不发起外部请求。

本机实测过的账号（口令问项目负责人，仓库里不存）：

| 项 | 值 |
|---|---|
| 库名 | `used_car` |
| 业务账号 | `usedcar_app` |
| 权限范围 | 仅 `used_car.*`，无权限访问其他库 |
| root 账号 | `root`，只用于管理，业务配置不用它 |

3. 编译并启动

```bash
cd backend
mvn -DskipTests package

# 回到项目根目录加载 .env（数据库口令 + AI Key 都在里面），再回来启动
cd ..
set -a; source .env; set +a
cd backend
java -jar target/usedcar-server-1.0.0.jar --server.port=8080
```

`source .env` 这一步不能省。只敲 `java -jar ...` 会同时踩两个坑：
数据库连不上（`DB_PASSWORD=123456` 覆盖配置）、AI 收车返回 503（没有 `USEDCAR_AI_API_KEY`）。
`.env` 已被 `.gitignore` 忽略，密钥不会进仓库。

**五个必须知道的坑：**

- **端口必须显式给。** 当前 shell 的环境变量里存在 `SERVER__PORT=63764`（WorkBuddy 自己的服务占用），
  Spring Boot 会把它当成 `server.port`，导致启动报 `Port 63764 was already in use`。
  加 `--server.port=8080` 覆盖即可，命令行参数优先级最高。
- **密码变量名不能叫 `DB_PASSWORD`。** 本机用户级环境变量里已有 `DB_PASSWORD=123456`（解愠用），
  Spring Boot 的优先级是「命令行参数 > 系统属性 > 操作系统环境变量 > application.yml」，
  通用变量名会被旧值覆盖，症状是 `Access denied for user 'usedcar_app'@'localhost'`。
  所以本项目用 `USEDCAR_DB_PASSWORD`，见 `application.yml` 注释。
- **中文路径下不要用 `mvn spring-boot:run`。** 项目路径含「项目/二手车」，Maven 给子进程传
  classpath 时会按 GBK 截断，报 `ClassNotFoundException: 主类`。用 `package` + `java -jar` 绕开 fork。
- **改后端代码后重新打包要先停服务。** 运行中的 java 进程锁着 target 里的 jar，
  `mvn package` 会在 repackage 阶段报 `Unable to rename ...jar.original`。先停 8080 再打包。
- **换了 AI Key 必须重启后端。** `.env` 只在 `source .env` 那一刻被读进 shell，
  再由 `java -jar` 固化进进程环境变量；改文件不会让运行中的进程重读。
  旧 Key 失效后的症状是 AI 收车返回 `code=502 / AI 服务拒绝请求，请检查模型 ID 与密钥是否正确`。
  只改 `.env` 一行即可，代码、`application.yml` 的占位符、`.env.example` 都不用动；
  前端 5174 只做代理，也不用重启。用 IntelliJ 的 Run Configuration 启动时，
  那里存了另一份环境变量副本，需要同步改。

4. 验证

- 健康检查 http://localhost:8080/api/health：新建库并执行 V1 + V7 + V9 + V10 + V13 后，`ddlTableCount` 为 16，`wiredTableCount` 为 16；`expectedDdlTableCount` 硬编码为 16。

  两个表数口径不要混：`ddlTableCount` 是数据库当前实际表数，`wiredTableCount` 是 Java 侧带 `@TableName` 注解接入的表数，从 MyBatis-Plus 元数据读取。当前脚本口径为 **16 张建表 / 16 张接线 / 0 张预留**：V7 的 AI 会话 2 张、V9 的申请单、V10 的流转记录、V13 的 Agent 轨迹表，以及最后接线的 `sale_order` / `after_sale` 全部已接线，没有预留表了。V6 已于 2026-11-09 在备份后执行，
本机两张视频历史表已清除，`ddlTableCount` 现在就是 16，与期望值一致。

- 车辆列表 http://localhost:8080/api/vehicles?page=1&size=10 → 未登录时返回 `code: 401`
- 接口文档 http://localhost:8080/swagger-ui.html

5. 前端

```bash
cd frontend
npm install          # 首次
npm run dev          # http://localhost:5174
```

Vite 已把 `/api` 代理到 `http://localhost:8080`，前端代码里只写相对路径，不用管端口和跨域。

**`http://localhost:5174` 和 `http://127.0.0.1:5174` 都能打开。**
`vite.config.js` 里 `server.host: '::'` 让 Node 开 dual-stack，同一个监听同时接受
IPv6 `::1`（localhost）和 IPv4 映射地址，不再有「只能敲 localhost」的限制。
代价是会监听所有网卡，只适合本机开发学习；公共网络或办公网里把这一行去掉
（回到只绑 `::1`）或改成 `'127.0.0.1'`。

**排查时 curl 要加 `--noproxy '*'`。** 本机 shell 配了 HTTP 代理，
直接 `curl http://localhost:8080/api/health` 会拿到代理返回的 502
「由于目标计算机积极拒绝，无法连接」，看起来像后端挂了，其实是请求根本没到本机。
浏览器一般会自动绕过 localhost，但装了代理插件时要确认 bypass 规则。

启动后依次验证：

```bash
curl --noproxy '*' http://localhost:8080/api/health        # 期望 dbConnected:true
curl --noproxy '*' http://localhost:5174/                  # 期望 200
curl --noproxy '*' http://localhost:5174/api/health       # 验证 Vite 代理能连到后端
```

页面：登录 → 车辆列表（搜索/分页/收车入库）→ 车辆详情（定价测算 / 成本明细 / 状态流水 / 证件）→ 库存预警（处理与手动巡检）。

## 开发阶段

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| 0 | 骨架 + 11 张表（V7 后 13 张，V9 后 14 张，V10 后 15 张） | `/api/health` 返回 `expectedDdlTableCount=15` |
| 1 | 车辆全流程 CRUD + 状态机 | 收车→在售→成交→售后→清库 五态流转，成交和售后支持按业务需要撤回，非法流转被拒绝 |
| 2 | 定价与滞销 | 保本底价、资金成本、阶梯降价全部从 `pricing_rule` 读，改表即生效；每日巡检产出 `stock_alert` |
| 3 | Vue3 前端 | 车辆列表、车辆详情、库存预警 |
| 4 | AI 收车 | 聊天式抽取 + 内嵌卡片补全 + 人工确认后才写库，会话与消息长期保存 |
| 5 | 员工与归属 | 员工账号 CRUD + ADMIN 权限收口；每辆车记录录入人与对接人 |
| 6 | 车辆审批 | 员工写操作落申请单，老板批准后才执行；批准后归属记申请人 |
| 7 | 离职交接 | 在职状态筛选；离职时未完成车辆转给接手人，原对接人留痕不删 |
| 8 | 订单与售后 | 成交登记写 sale_order、售后成本计入总成本、清库前售后闭环，两张预留表清零 |
| 9 | 前端 UI 改版 | 设计令牌 `theme.css`、自绘深色侧栏、工作台 `/dashboard`、6 个页面重做，修多处交互缺陷 |
| 10 | 产品化改造 | 品牌「优驰云」、登录页 ToB 化、全站去开发腔、单号与车辆引用改业务编号、测试数据改名 |
| 11 | Agent 工具调用 | 意图路由 + 通用受控只读 SQL + 结果回灌 + 双层防幻觉，写权限保持收紧 |

> 阶段 9、10 由并行的另一条会话完成，本文件只按工作记忆同步摘要；
> 阶段 11 是本次 Agent 改造。编号按时间顺序，不表示重要性。

## 接口清单（已完成）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录，返回 token |
| GET | `/api/auth/me` | 当前登录人 |
| POST | `/api/auth/logout` | 退出登录 |
| GET | `/api/vehicles` | 分页查询，支持 `keyword` / `status` |
| GET | `/api/vehicles/{id}` | 详情，含成本合计与状态流水 |
| GET | `/api/vehicles/{id}/handovers` | 该车的对接权流转记录，按时间正序 |
| GET | `/api/vehicles/{id}/sale-orders` | 该车成交订单 |
| POST | `/api/vehicles/{id}/sale-orders` | 成交登记，写订单并把车推进 SOLD（员工提交申请） |
| GET | `/api/vehicles/{id}/after-sales` | 该车售后记录 |
| POST | `/api/vehicles/{id}/after-sales` | 新增售后单（员工提交申请） |
| POST | `/api/after-sales/{id}/close` | 售后结案（员工提交申请） |
| POST | `/api/vehicles` | 收车入库 |
| PUT | `/api/vehicles/{id}` | 修改基础信息（不含金额与状态） |
| POST | `/api/vehicles/{id}/status` | 状态流转，过状态机校验 |
| POST | `/api/vehicles/{id}/costs` | 追加成本（整备/运输/过户） |
| GET | `/api/vehicles/{id}/pricing` | 定价测算（资金成本/保本底价/建议价/滞销等级） |
| POST | `/api/vehicles/{id}/price-change` | 调整挂牌价并记录调价流水 |
| GET/POST | `/api/vehicles/{id}/certificates` | 车辆证件列表与新增 |
| GET | `/api/certificates/expiring` | 到期证件列表 |
| GET | `/api/alerts` | 待处理预警（DANGER 优先） |
| POST | `/api/alerts/{id}/handle` | 处理预警（RESOLVED / IGNORED） |
| POST | `/api/alerts/inspect` | 手动触发巡检 |
| GET | `/api/customers` | 客户分页查询，支持 `keyword`（姓名/手机号）/ `customerType` |
| GET | `/api/customers/{id}` | 客户详情 |
| POST | `/api/customers` | 新增客户，手机号查重、类型与等级校验 |
| GET | `/api/ai/conversations` | 当前登录人的 AI 收车会话列表（按更新时间倒序） |
| POST | `/api/ai/conversations` | 新建会话 |
| GET | `/api/ai/conversations/{id}` | 会话详情，含全部消息与每轮的卡片快照 |
| POST | `/api/ai/conversations/{id}/messages` | 发一句自然语言，返回 AI 回复 + 最新卡片 |
| PATCH | `/api/ai/conversations/{id}/card` | 直接改卡片字段 / 点「跳过建议字段」 |
| POST | `/api/ai/conversations/{id}/card/confirm` | 老板确认，才调业务工具写库，返回车辆 id |
| POST | `/api/ai/conversations/{id}/card/discard` | 放弃当前卡片 |
| GET | `/api/employees` | 员工列表，支持 `keyword`（登录名/姓名/手机号），登录即可读 |
| POST | `/api/employees` | 新增员工（仅 ADMIN） |
| PUT | `/api/employees/{id}` | 改姓名/手机号/角色（仅 ADMIN） |
| POST | `/api/employees/{id}/status?status=0\|1` | 停用或启用（仅 ADMIN） |
| POST | `/api/employees/{id}/password` | 重置密码（仅 ADMIN） |
| GET | `/api/employees?employmentStatus=ACTIVE\|LEFT` | 按在职状态筛选（不传返回全部） |
| GET | `/api/employees/{id}/pending-handover-count` | 该员工名下待流转的未完成车辆数（仅 ADMIN） |
| POST | `/api/employees/{id}/leave` | 办理离职并批量流转对接权（仅 ADMIN） |
| GET | `/api/approvals?scope=pending\|mine\|all` | 申请列表，pending / all 仅 ADMIN |
| GET | `/api/approvals/spec` | 复制重提表单的字段规格 |
| POST | `/api/approvals/{id}/approve` | 批准并执行业务写入（仅 ADMIN） |
| POST | `/api/approvals/{id}/reject` | 驳回，必须带原因（仅 ADMIN） |
| POST | `/api/approvals/{id}/resubmit` | 复制被驳回的申请，改参数后重提 |
| POST | `/api/approvals/{id}/cancel` | 撤回自己待审批的申请 |

登录后带请求头 `satoken: <tokenValue>`。初始管理员 `admin / admin123`，由 `DataInitializer` 首次启动时创建。

## AI 收车（阶段 4，阶段 11 增强为带工具调用的 Agent）

车辆列表页点「AI 收车」进入聊天界面。卡片**内嵌在消息流里**，不是浮层弹窗；确认后卡片变灰只读，但消息和当时的卡片快照都留在库里，随时可回查。

**读写分离的边界**（阶段 11 修订）。早期版本把 Agent 完全隔离在
`ai_conversation` / `ai_message` 两张表里，连查询都不让碰业务表，结果老板问
「现在库存里有多少台车」只能回「查不了，麻烦说一下品牌车型」——那是把
「防止模型乱写库」错误地扩大成了「不许模型读数据」。现在按读写分开：

- **写**：模型永远碰不到。收车写库仍是老板点确认后由 `CreateVehicleTool.execute()`
  调用 `VehicleService.create()` 这个方法，一次事务写三张表；模型只产出 `VehicleCreateDTO` 的参数。
- **读**：模型只能输出「调哪个固定工具」或「一条只读 SELECT」。固定工具名过
  `ToolRegistry` 白名单、参数过 `argKeys` 白名单；通用 SQL 过 `ReadonlySqlTool` 的
  语法 / 表 / 列三层校验。模型拿不到 Mapper、拿不到 `Connection`，也拿不到结果之外的东西。

### 只读问答 Agent（阶段 11）

**读能力不是按问题穷举工具**。早期版本每换一种问法就加一个工具，结果问「在手上超过一个月的有几台」
模型只能拿总台数去凑，甚至编出「21 台」。现在读权限整体放开成**通用受控只读 SQL**，
写权限一层不动。

`AgentQueryService` 两步走：

1. **意图路由**：把固定工具清单、通用 SQL 工具说明、`SchemaCatalog` 里的业务库结构一起拼进 prompt，
   模型输出 `QUERY`（固定工具）/ `QUERY`（`run_readonly_sql` + SQL）/ `INTAKE` / `CHAT`。
   高频简单问题走固定工具（`count_vehicles`、`vehicle_status_summary`、`find_vehicle`、
   `count_pending_approvals`、`count_open_after_sales`），长尾问题（库龄、均价、区间、分组、跨表 JOIN）
   **必须生成 SQL**，prompt 明令禁止拿相近的固定工具凑数。判 `INTAKE` 就返回 null，交给原抽取器。
2. **执行 + 回灌**：Java 校验并执行 SQL，把结构化结果**连同实际执行的 SQL** 再发一轮模型说人话。
   SQL 执行失败会把错误反馈给模型改写一次，仍失败就如实报错，不猜结果。

**`ReadonlySqlTool` 的安全边界**（这是「读放开」不等于「放权」的关键）：只允许单条 `SELECT`；
禁止写关键字 / 多语句 / 注释 / `SELECT *`；`FROM`/`JOIN` 表名必须落在 `SchemaCatalog.ALLOWED_TABLES`
白名单（14 张业务表，`ai_message` 聊天原文排除）；敏感列黑名单（`password`、`id_card`、
`draft_json`、`card_json`、`payload_json`）出现即拒绝；强制 `LIMIT 200`，执行后再裁一次行列。
模型拿不到 `Connection`、拿不到 `DataSource`、没有任何写方法入口。
实测「把 UC20261109001 的状态改成已清库」被回绝：「改车辆状态属于写操作，需要你在门店系统里操作」。

**防幻觉做两层**（用户明确要求「不知道的数据直接说不知道」）：
`ANSWER_PROMPT` 硬性规定只能用结果里的数字、`rowCount=0` 就说没查到、不许推测估算；
Java 侧再加 `guardNumbers()` —— 答案里每个数字都必须在查询结果或用户原话里按**数值**（不是字符串）
找得到，否则判幻觉替换成「这个我没查到，不敢瞎说」。数值比较是必须的：SQL 返回 `64560.000000`，
模型说 `64560 元` 是同一个数，按字符串比会误杀。

**口径统一（软约束，不是硬保证）**：`SchemaCatalog.SCHEMA_TEXT` 里约定
「库存 = `status <> 'CLEARED' AND deleted = 0`」、「库龄 = `DATEDIFF(CURDATE(), purchase_date)`」、
中文状态标签由 Java 侧唯一提供，以及第 7 条「凡车辆统计默认带这两个条件」。
重启后实测「收车价超 10 万」和「价格十万以上」两种问法都答 2 台，修掉了之前的 3 台 / 2 台漂移。
**注意这是提示词约束**：模型换个措辞或换模型仍可能不带条件，Java 侧没有强制改写 SQL；
要硬保证得在 `ReadonlySqlTool.validate()` 里对涉及 `vehicle` 的统计做条件注入，
但那会误伤「包含已清库」这类合法查询，目前没做。

安全边界不靠嘴说：`backend/src/test/java/com/usedcar/agent/tool/ReadonlySqlToolTest.java`
有 5 个用例，直接拿 `UPDATE` / `DELETE` / 堆叠注入 / `mysql.user` / `password` 列去撞校验层，
`mvn -Dtest=ReadonlySqlToolTest test` 实测 5/5 通过。这证明的是**即使模型真生成了写语句**，
Java 侧也会在碰数据库之前拒绝，而不是只靠模型自己说「我不能改」。

### 2026-11-10 一次性修正：读口径与误杀

- 「读权限完全放开」的准确含义是 **业务数据全部可读、隐私字段本来就排除**，不是要不要保护隐私的取舍。
  按此核对：16 张表里 14 张业务表全在 `ALLOWED_TABLES`（`ai_message` 聊天原文、
  `ai_agent_trace` 运行轨迹排除，后者是运维日志，不是经营数据）；
  隐私黑名单补齐 `phone` / `cert_no`（**原来漏了客户电话和证件编号**），
  `SchemaCatalog.SCHEMA_TEXT` 里同步标注禁止查询。单测加了这两列，6/6 通过。
- **`guardNumbers()` 判据重做**。原来要求「答案里每个数字都必须在结果 JSON 里字面出现」，
  实测把合理归纳误杀两次：`64560.000000` vs `64560`；以及工具返回 4 台车、
  模型归纳「1 台已清库、3 台已收车」被判幻觉（截图里「有朗逸吗」答「没查到」就是这个）。
  现在**只拦「结果里一个数字都没有，答案却报了数字」**。防「拿总数凑条件结果」靠
  路由层强制带条件 SQL + `ANSWER_PROMPT`，不靠数字白名单硬拦——
  否则每出现一种合理归纳就要补一条规则，变成打补丁。
- **空结果 Java 硬兜底**：`emptyResultAnswer()` 检测 `rowCount/count/total/pending/open` 为 0，
  直接答「没有查到符合条件的数据。」，不经模型润色。
- **非收车输入统一入口**：`AiIntakeService.chat()` 在 `cardStatus=NONE` 且零抽取字段时给能力说明，
  不再掉进抽取器的「这句我没解析出来」。只在 NONE 生效，DRAFT 会话里
  「收车价改成 7 万」照常补全（已实测 60000 → 70000）。
- `ANSWER_PROMPT` 补「允许基于结果做归纳」「金额台数一律阿拉伯数字」；
  `ROUTER_PROMPT` 补「写操作指令判 CHAT 明确回绝」。

实测：有朗逸吗 → 4 台（1 台已清库、3 台已收车）、有劳斯莱斯吗 → 没有查到符合条件的数据、
客户电话 → 明确回绝、删车 → 明确回绝、均价 63857.14 元、品牌最多大众 10 台、
库龄 3 台、收车多轮改成 7 万 → 70000。

实测长尾问题：超过一个月 3 台、平均收车价 63857.14 元、品牌最多是大众 10 台、
收车价超 10 万 2 台、售后成本 1200、本月成交 2 单、跨表查「李娜提了几条待审批」4 条、
查不到的客户答「没有查到符合条件的数据」。查询不产生卡片、不改草稿、不改 `card_status`，
已确认 / 待审批的会话里也能继续问。无 Key 的 mock 环境用关键词命中固定工具，
长尾问题如实说明不支持，不编答案。

字段口径：**4 个硬必填**（品牌 / 车型 / 收车价 / 收车日期，与 `VehicleCreateDTO` 的校验注解一致）必须补齐；
**4 个建议字段**（VIN / 首次上牌日期 / 里程 / 车牌号）会追问，但允许老板点「跳过这些直接确认」。
挂牌价、备注可在卡片里直接填。硬必填没齐时确认按钮不可用。

抽取器可插拔：`IntakeExtractor` 接口两个实现，`SiliconFlowExtractor` 走真实模型（默认），
`MockIntakeExtractor` 走本地关键词，由 `USEDCAR_AI_PROVIDER` 决定注入哪个，切换不用改业务代码。
模型要求返回严格 JSON，Java 侧再做一次白名单过滤与类型归一化，模型输出的额外字段一律丢弃。
确认动作靠 `card_status` 的 CAS 更新 + `uk_conv_vehicle` 唯一键双保险，连点两次不会插出两辆车。

执行建表（含中文注释，必须带字符集参数，否则按 GBK 解释会乱码）：

```bash
"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
    --default-character-set=utf8mb4 < sql/V7__ai_intake.sql
```

### 执行轨迹落库（阶段 12）

Agent 每执行一个工具都往 `ai_agent_trace` 落一条：第几轮、工具名、模型给的参数、
**最终真正执行的那条 SQL**、结果条数、耗时、成功与否、失败原因。
前端右侧「执行轨迹」面板直接读这张表，点开任何一步都能看到当时的查询语句和结果。

为什么要有这张表：多轮循环里「哪一轮选错了工具、改写后的 SQL 长什么样、慢在哪一步」，
以前只活在内存 `List<Step>` 和一行 `log.info` 里，请求结束就没了，答错了只能猜，
也拿不出准确率和耗时分布的数据。

三个设计取舍：

- **落库失败不允许影响回答**：`saveTrace()` **这个方法**整段包在 try 里，表锁了、字段超长了
  都只打 warn 日志。轨迹是诊断数据不是业务数据，不能因为它让老板拿不到答案。
- **记「改写后」的 SQL，不记模型最初给的那条**：`runSqlWithRetry()` **这个方法**返回
  `SqlRun(String sql, Map result)` **这个 record**，首次执行失败、模型改写成功时，
  落库的是改写后真正跑通的那条。排查时看原始 SQL 会得出错误结论。
- **轨迹不进只读查询白名单**：它是运维日志不是经营数据，让模型能查自己上一轮写了什么属于自指。
  16 张表里 14 张在 `SchemaCatalog.ALLOWED_TABLES`，排除 `ai_message`（聊天原文）
  和 `ai_agent_trace`（运行轨迹）。

接口 `GET /api/ai/conversations/{id}/traces`，归属校验与聊天记录同级，
改 URL 看不到别人的轨迹。表按 `(conversation_id, id)` 建索引，回放不 filesort；
`(tool, created_at)` 索引留给「哪个工具最慢、失败率最高」这类聚合。

执行建表：

```bash
"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
    --default-character-set=utf8mb4 < sql/V13__agent_trace.sql
```

实测：问「库里还有几台车」→ 一条轨迹 `count_vehicles` / 21 条 / 5ms；
问「大众的收车均价是多少，比全店均价高还是低」→ 一条 `run_readonly_sql` / 34ms，
完整 SQL 落库；失败场景 `ok=0`，拒绝原因写进 `error_msg`，前端标红。

## 当前状态

- 阶段 0 完成：11 张表建好；V7 追加 AI 会话 2 张、V9 追加申请单 1 张、V10 追加流转记录 1 张后，
  `/api/health` 返回 `expectedDdlTableCount: 15`（V13 加轨迹表后为 16）。
- 阶段 1 完成：登录鉴权 + 车辆五个写接口 + 五态状态机（含成交/售后撤回）+ 成本明细 + 状态流水。
  非法流转实测被拒（跳步、倒流、终态回退、乱传状态值），清库后禁止补录成本。
- 阶段 2 完成：定价全部读 `pricing_rule`（14 个参数），规则改表 30 秒内生效，
  实测把第三档天数从 75 改成 200，持有 96 天的车建议价从 13% 档回落到 8% 档。
  每日巡检产出滞销、毛利过低、证件到期三类预警，重复执行不产生重复预警。
- 阶段 3 完成：Vue3 + Vite + Arco Design 前端，四个页面（登录 / 车辆列表 / 车辆详情 / 库存预警），
  详情页内含定价测算、成本、流水、证件四个区块。
- 阶段 4 完成：AI 收车聊天页 + 内嵌卡片 + 人工确认后写库，会话与消息长期保存。
- 阶段 5 完成：员工管理页 + ADMIN 权限收口 + 车辆录入人/对接人记录。
- 阶段 6 完成：审批中心，员工全部车辆写操作（含证件与 AI 收车）改为提交申请，
  老板批准后才执行；批准后 `created_by` / `owner_user_id` 记申请人、`approved_by` 记审批人。
- 阶段 7 完成：员工在职状态筛选；办理离职时把未完成车辆对接权流转给接手人，
  原对接人写进流转记录，历史数据一条不删。
- 阶段 8 完成：`sale_order` / `after_sale` 接线，成交登记 → 订单 → 状态推进 SOLD、
  售后成本计入总成本、未结案不许清库；视频模块残留彻底清理；Vite 双栈监听。
  **16 张表全部接线，0 张预留**。
- 阶段 9 完成（并行会话）：前端 UI 深度改版，新增设计令牌、深色侧栏与工作台，修多处交互缺陷。
- 阶段 10 完成（并行会话）：产品化改造，品牌「优驰云」、登录页 ToB 化、全站去开发腔。
- 阶段 11 完成：AI 收车从「只会抽字段」升级成带工具调用的 Agent。
  读权限放开成通用受控只读 SQL（库龄、均价、区间、分组、跨表都能查），
  写权限保持收紧；prompt + Java 数字校验双层防幻觉，不知道的直接说不知道。
- 阶段 12 完成：Agent 执行轨迹落库。每轮工具调用记工具名、参数、实际执行的 SQL、
  结果条数、耗时、成功与否，前端右侧面板可视化，点开可看当时的查询与结果。
  表数 15 → 16，`/api/health` 三个字段实测 16 / 16 / 16。

## 员工与车辆归属（阶段 5）

角色：老板持 `ADMIN`，员工持 `SALES` / `FINANCE`。`/api/employees` 的**写操作**统一由
`EmployeeService.requireAdmin()` **这个方法**校验，**读列表只需登录**（车辆列表页要拿它做姓名映射、
收车表单要拿它做对接人下拉）。前端菜单按 `/api/auth/me` 返回的 role 隐藏「员工管理」，
这只是体验层，不是安全边界。

两个归属字段，别混：

| 字段 | 含义 | 谁决定 |
|---|---|---|
| `vehicle.created_by` | 谁把这条记录录进系统的 | **不在任何 DTO 里暴露**。ADMIN 直接录入取登录态；员工申请经批准后取申请人 id（`VehicleService.create()` 的三参重载），绝不记成审批的老板 |
| `vehicle.owner_user_id` | 谁对接/负责这辆车 | 收车表单可指定；不指定时后端默认取当前登录人 |

老板用自己的 `admin` 账号收车时，两个字段都记成 admin 的 id，不搞特殊。AI 收车确认后经
`CreateVehicleTool` 走同一个 `create()` 这个方法，同样有记录。

`sql/V8__employee_module.sql` 只给 `vehicle` 加 `created_by` 列和索引，**当时不加表**；
V9 加了 `vehicle_approval` 表和 `approved_by` 列，V10 又加了 `vehicle_handover` 表和
`sys_user.employment_status` 列；阶段 8 接线订单与售后后，当前口径为 16 张建表 / 16 张接线 / 0 张预留。
升级前录入的历史车辆 `created_by` 为 NULL，
前端显示「—」；凭猜测把老数据全算到 admin 头上是伪造记录，脚本里给的是可选的手工回填 SQL，
默认不执行。状态流水的 `operator_id` 现在也会在详情页显示成姓名。

员工账号**只停用不删除**：删掉会让 `created_by` 和状态流水的 `operator_id` 变成孤儿。
另外禁止停用自己的账号、禁止把自己的 `ADMIN` 角色改掉，避免出现没有管理员能操作的死局。

## 车辆审批（阶段 6）

**权限模型**：`ADMIN` 的写操作直接执行；`SALES` / `FINANCE` 的全部车辆写操作一律变申请单。
判断在后端 `ApprovalGate.submitIfEmployee()` **这个方法**里，不在前端——前端按角色选接口能被绕过，
员工直接调 `POST /api/vehicles` 一样会落申请单。写接口统一返回 `WriteResult`：
`executed=true` 是已执行、`executed=false` 是已提交申请，前端只判断这个字段决定要不要跳详情页。

覆盖 7 类操作：收车入库、AI 收车录入、改基础信息、状态流转、调价、追加成本、新增/删除证件。
读接口（列表、详情、定价、证件）不限制，员工能看车但不能改。

**为什么用独立申请表**：让 `vehicle` 先进「待审批」状态会污染库存列表、滞销巡检和定价测算，
得在每一处补 `status <> 'PENDING'`，漏一个就算出错误数据。`vehicle_approval.payload_json`
存的是提交那一刻的参数快照，批准时按快照执行，不现查业务表最新值。

**归属规则**（用户 2026-11-09 拍板）：批准后 `created_by` 和 `owner_user_id` 都记**申请人**，
`approved_by` 记审批人。这样「谁用 AI 收车、谁对接」落在员工账号上，不会全变成老板。
`VehicleService.create()` 因此多了一个三参重载，只允许服务端编排层调用，Controller 不得直接调。

批准动作靠 `vehicle_approval` 的 `casApprove`（`UPDATE ... WHERE status='PENDING'`）和业务写入
**同一个事务**：CAS 成功但写车失败会整体回滚到 PENDING，老板能重试；两个管理员同时点通过，
第二个影响行数为 0，不会插出两辆车。

**AI 收车分账号**：员工点「确认录入」不写库，卡片变 `PENDING`（灰、只读），生成
`AI_INTAKE_CREATE` 申请单；老板点确认走原来的直接入库路径。`PENDING` 和 `CONFIRMED`
都是会话终态，继续聊天不会把卡片改回 DRAFT，防止审批途中重复提交。

**复制重提**：被驳回或撤回的申请可改参数重提，生成新单、原单留作历史。可编辑字段清单由
`GET /api/approvals/spec` 提供（后端 `ApprovalFieldSpec`），前端不自己维护一份；
不在清单里的键（如 `ownerUserId`）沿用原值，防止员工顺手把车改挂到别人名下。

**AI 会话卡片状态同步**：早期版本里申请被批准后卡片仍显示 PENDING，V11 已修。
`vehicle_approval.source_conversation_id` 记下来源会话，`ApprovalService.approve()` 在车辆写入成功后
调 `AiIntakeService.markApproved()` 回写卡片为 `CONFIRMED`，两者同一个事务；
历史卡片快照与聊天消息一条不删，员工刷新会话页就能看到「申请已通过 + 库存编号」。

剩下唯一的已知限制：证件删除申请没有可改参数，只能原样重提。

阶段 5、6 的建表脚本要**按编号顺序**执行（V10 见下一节；含中文注释，必须带字符集参数）：

```bash
"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
    --default-character-set=utf8mb4 < sql/V8__employee_module.sql
"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
    --default-character-set=utf8mb4 < sql/V9__vehicle_approval.sql
```

## 员工离职与车辆交接（阶段 7）

**先说清「未完成订单」的口径**：做阶段 7 时 `sale_order` 还是预留表、没有业务实现
（阶段 8 才接线），所以这里的「未完成」不指订单表，而指
**`vehicle.owner_user_id` = 离职员工 且 `status <> 'CLEARED'` 的车辆**。
`CLEARED`（已清库）视为完成，不参与流转。如果你要的是真正的订单表，那是另一块业务，得先接线。

**两个状态维度，别混**：`sys_user.status` 是登录开关（1 启用 / 0 停用），
`sys_user.employment_status` 是人事状态（`ACTIVE` 在任 / `LEFT` 离职）。
临时停用的在职员工不会被当成离职。办理离职时两个字段一起改：
`employment_status=LEFT` + `status=0`，账号本身**只停用不删除**，
否则 `vehicle.created_by` 和状态流水的 `operator_id` 会变成孤儿。

管理员在员工管理页按「在任 / 离职」筛选；点「办理离职」时弹窗会先显示
**将流转几辆未完成车辆**，并要求指定一个在职且启用的接手人、填流转原因。

**留痕设计**：直接 `UPDATE vehicle.owner_user_id` 会把「原来是谁对接的」抹掉，
所以 `vehicle_handover` 表只追加不删除，一跳一行，记
`from_user_id` / `to_user_id` / `operator_id`（办理人）/ 原因 / 时间。
一辆车被流转两次（A 离职→B，B 又离职→C）能查到完整链路，详情页「流转记录」页签按时间正序展示。

**流转只改 `owner_user_id`**，以下全部原样保留：`created_by`（谁录入的）、
`vehicle_status_log`、`vehicle_cost` / `price_change_log`、`vehicle_approval`、
`ai_conversation` / `ai_message`。离职员工的未审批申请单和 AI 会话属于历史记录，不流转、不删除。

**事务与幂等**：`EmployeeService.leave()` 先 `casLeave`
（`UPDATE ... WHERE employment_status='ACTIVE'`）抢锁，再批量改车辆和写流转记录，
全部在同一个事务里，中途失败整体回滚；两个管理员同时点，第二个 CAS 影响行数为 0 被拒绝。
另有三道校验：不能给自己办离职、接手人不能是本人、接手人必须在职且启用。

执行建表（按 V8 → V9 → V10 顺序，含中文注释，必须带字符集参数）：

```bash
"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
    --default-character-set=utf8mb4 < sql/V10__employee_handover.sql
```

## 订单与售后闭环（阶段 8）

`sale_order` / `after_sale` 原先只是 V1 建好的预留表，Java 代码零引用。现在完整接线，
**16 张表全部接线、0 张预留**，`wiredTableCount` 实测 16。

**成交登记是独立接口，不是状态流转的副作用。** 如果点一下「成交」就顺手插订单，
会造出没有买方、没有成交价的脏记录，而且 `sale_order.uk_vehicle_id` 这个防重复成交约束
永远触发不了。现在 `POST /api/vehicles/{id}/sale-orders` 先校验「车辆在 ON_SALE + 客户存在 +
该车无订单」，写订单（号 `SO + yyyyMMdd + 三位流水`），再调 `VehicleService.changeStatus()`
把车推进 `SOLD`，两步同一个事务。成交销售默认取 `vehicle.owner_user_id`，不给前端伪造归属的机会。

**售后成本真的进成本合计。** `after_sale.cost` 大于 0 时，同时写一笔
`vehicle_cost`（类型 OTHER），定价测算里的 `totalCost` 才会跟着涨，
不会出现「有售后单但毛利没变」。实测 50000 → 51200。

**V1 注释里承诺的规则终于落地。** DDL 写着「清库前必须售后闭环，只要还有未完结的售后单，
车辆不允许流转到 CLEARED」，以前只在注释里。`VehicleService.changeStatus()` 现在加第二道闸：
目标是 `CLEARED` 且不是撤回清库时，查 `after_sale` 里 `status = 'OPEN'` 的条数，大于 0 直接拒绝。
实测未结案清库返回 `400 / 还有 1 条未结案的售后单，不允许清库`，结案后清库成功。

**三个写操作同样过审批闸门**：`CREATE_SALE_ORDER` / `ADD_AFTER_SALE` / `CLOSE_AFTER_SALE`，
员工提交申请、老板批准后由 `ApprovalService` 分派到 `OrderService`。实测员工成交登记
`executed=false`，批准后订单生成、车辆变 SOLD。

前端车辆详情页新增「成交订单」「售后」两个页签，成交表单的客户下拉来自 `/api/customers`。

实测 11 步全过：`SO20261109001` 生成、车状态 SOLD、重复成交 400、
售后成本 50000→51200、未结案清库 400、结案后清库成功、员工申请批准后订单生成、
health 三个表数全部为 15、视频残留只剩备份与 V6 脚本、双栈两地址均 200。

## 已有数据库清理

视频模块已彻底清理（2026-11-09）。执行前先用 `mysqldump` 把 `video_asset`（4 行）、
`video_task`（3 行）备份到 `backups/video_tables_20261109_1647.sql`，再手工执行
`sql/V6__remove_video_module.sql` 删除这两张表；源码空目录树、`target/classes` 里的编译残留、
`backend/uploads/videos/` 下 8 个 mp4/srt 生成文件、根目录 `uploads/videos/` 一并移除。
前端 `frontend/src` 与 `backend/pom.xml` 检索结果本来就零引用，`.gitignore` 的 `uploads/` 是通用规则，
这三项都没动。`V6` 脚本保留备查，`backups/` 已加入 `.gitignore`。

清理后全仓库只剩两处视频字样：备份文件和 V6 脚本本身。**不要通过重跑 `V1__init_schema.sql` 清理旧库**，
该脚本会重建业务表。原 V3 脚本随模块移除，V4、V5 保留原编号。
