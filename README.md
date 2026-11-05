# 二手车业务管理与短视频营销系统

全栈项目，目标：**能跑、能演示、面试能讲透**。不做生产级运维（不做多租户、不做 K8s 编排）。

## 技术栈

| 层 | 选型 | 用途 |
|---|---|---|
| 后端 | Spring Boot 3.3.5 / JDK 21 | Web 框架 |
| ORM | MyBatis-Plus 3.5.9 | 单表 CRUD、分页、逻辑删除 |
| 数据库 | MySQL 8.4 | 13 张业务表 |
| 鉴权 | Sa-Token 1.39 | 登录态、角色校验 |
| 文档 | springdoc-openapi 2.6 | Swagger UI 在线调接口 |
| 前端 | Vue 3 + Vite + Arco Design（阶段五） | 管理后台 |
| 图表 | ECharts（阶段五） | 库存与资金占用仪表盘 |
| 多媒体 | FFmpeg 8.1（本机已装） | 字幕烧录、音视频合成 |

## 目录结构

| 路径 | 作用 |
|---|---|
| `sql/V1__init_schema.sql` | 13 张表的建表语句 + `pricing_rule` 初始参数 |
| `backend/pom.xml` | Maven 依赖，锁定 Boot 3.3.5 / JDK 21 |
| `backend/src/main/java/com/usedcar/UsedCarApplication.java` | 启动类，开 `@EnableScheduling` 与 `@EnableAsync` |
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

2. 配置数据库口令（不进仓库）

口令不写在代码里，通过环境变量 `USEDCAR_DB_PASSWORD` 注入。

```bash
# 首次：复制模板
cp .env.example .env

# 编辑 .env，把占位符换成你本机的真实口令
# 每次开新终端先加载
set -a; source .env; set +a
```

`.env` 已在 `.gitignore` 里，不会进仓库。用 IntelliJ 跑的话，在 Run Configuration 的
Environment variables 里加一条 `USEDCAR_DB_PASSWORD=你的口令` 即可。

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
java -jar target/usedcar-server-1.0.0.jar --server.port=8080
```

或者开发期热启动：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
```

**两个必须知道的坑：**

- **端口必须显式给。** 当前 shell 的环境变量里存在 `SERVER__PORT=63764`（WorkBuddy 自己的服务占用），
  Spring Boot 会把它当成 `server.port`，导致启动报 `Port 63764 was already in use`。
  加 `--server.port=8080` 覆盖即可，命令行参数优先级最高。
- **密码变量名不能叫 `DB_PASSWORD`。** 本机用户级环境变量里已有 `DB_PASSWORD=123456`（解愠用），
  Spring Boot 的优先级是「命令行参数 > 系统属性 > 操作系统环境变量 > application.yml」，
  通用变量名会被旧值覆盖，症状是 `Access denied for user 'usedcar_app'@'localhost'`。
  所以本项目用 `USEDCAR_DB_PASSWORD`，见 `application.yml` 注释。

4. 验证

- 健康检查 http://localhost:8080/api/health → `dbConnected: true`、`tableCount: 13`
- 车辆列表 http://localhost:8080/api/vehicles?page=1&size=10 → 未登录时返回 `code: 401`
- 接口文档 http://localhost:8080/swagger-ui.html

## 五个阶段

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| 0 | 骨架 + 13 张表 | `/api/health` 返回 `tableCount=13` |
| 1 | 车辆全流程 CRUD + 状态机 | 收车→在售→成交→售后→清库 五态流转，非法流转被拒绝 |
| 2 | 定价与滞销 | 保本底价、资金成本、阶梯降价全部从 `pricing_rule` 读，改表即生效；每日巡检产出 `stock_alert` |
| 3 | 异步任务架构 | 任务状态入库、启动补偿、幂等去重、三档超时、重试 3 次 |
| 4 | AI 短视频链路 | 文案改写 → 图生视频 → TTS → 字幕烧录 → 成片，全链路可跑 |
| 5 | Vue3 前端 | 车辆列表、车辆详情、仪表盘、视频任务进度 |

## 当前状态

- 阶段 0 骨架已就绪，待建库验证。
- 阶段 4 有前置阻塞：Seedance 图生视频、聆眸数字人、TTS 均需火山引擎 AK/SK，当前没有账号。
  方案：`VideoProvider` 接口抽象，先接 mock 实现产出占位素材，FFmpeg 合成与字幕烧录真实执行；
  拿到 AK/SK 只换实现类，不改架构。
