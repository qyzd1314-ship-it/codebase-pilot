# Database Profiles

当前项目支持两套数据库 profile：

- `h2`：本地快速调试使用，启动简单，不依赖外部数据库。
- `postgres`：正式运行 / 面试演示环境使用，适合保存真实历史数据和完整任务链路结果。

本轮仅完成 profile 拆分、PostgreSQL 兼容性修复、Flyway 接管和运行文档补充：

- 不引入 pgvector
- 不修改 Agent 主流程
- 不修改前端

## 1. 为什么保留 H2，又增加 PostgreSQL

### H2 适合什么场景

`h2` 适合：

- 本地快速启动
- 单人调试
- 临时验证接口和 Agent 流程
- 不想额外准备数据库环境时

### PostgreSQL 适合什么场景

`postgres` 适合：

- 正式运行
- 面试演示
- 持久化真实历史数据
- 多次重启后仍保留 Repo / Task / Artifact 结果

### 为什么不再把 H2 作为正式运行数据库

H2 更像一个嵌入式开发数据库，优点是轻量和易启动，但不适合作为正式演示或长期运行数据库，原因包括：

- 对 PostgreSQL / MySQL 这类真实生产数据库的行为模拟不完全一致
- 长文本字段、索引、DDL 变更和兼容性验证不如真实数据库可靠
- 在面试演示场景里，使用 PostgreSQL 更能体现项目的真实持久化设计

## 2. PostgreSQL 持久化了哪些核心数据

当前 PostgreSQL profile 用于持久化以下核心数据：

- `repo`：仓库信息、索引状态、文件数、chunk 数
- `code_chunk`：代码切块、文件路径、符号名、内容、哈希
- `agent_task`：任务主记录
- `agent_task_step`：Agent Timeline / Step 执行记录
- `agent_artifact`：Root Cause Report、Code Evidence 等交付物
- `agent_task_event`：任务事件流
- `agent_tool_call`：工具调用记录
- `agent_approval`：审批记录

说明：

- `EvidenceRef` 当前不是单独表，而是作为 JSON 文本附着在 step / artifact 中
- Eval 相关结构目前没有独立持久化表；如果后续接入持久化 eval，再落到 PostgreSQL

## 3. 配置文件说明

项目当前配置拆分如下：

- 公共配置：`src/main/resources/application.yml`
- H2 配置：`src/main/resources/application-h2.yml`
- PostgreSQL 配置：`src/main/resources/application-postgres.yml`

### 当前默认 profile

当前默认 profile 是：

```text
h2
```

这样做是为了保证本地开箱即用；如果用于正式运行或面试演示，建议显式切到 `postgres`。

## 4. H2 启动方式

### 4.1 直接启动

```bash
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=h2"
```

如果你没有显式指定 profile，项目默认也会使用 `h2`。

### 4.2 H2 连接信息

H2 核心配置：

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/codebase-pilot;MODE=MySQL;AUTO_SERVER=TRUE
    username: sa
    password:
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: update
```

H2 Console：

```text
http://localhost:8123/api/h2-console
```

JDBC URL：

```text
jdbc:h2:file:./data/codebase-pilot
```

## 5. PostgreSQL 启动方式

### 5.1 使用 Docker Compose 启动 PostgreSQL

项目根目录新增了：

```text
docker-compose.postgres.yml
```

启动命令：

```bash
docker compose -f docker-compose.postgres.yml up -d
```

停止命令：

```bash
docker compose -f docker-compose.postgres.yml down
```

如果希望保留数据库数据，直接 `down` 即可；因为 compose 文件已经挂了 volume。

### 5.2 PostgreSQL 默认参数

Docker Compose 默认配置：

- image：`postgres:15`
- database：`codebase_agent`
- username：`postgres`
- password：`postgres`
- port：`5432`

### 5.3 PostgreSQL profile 环境变量

`application-postgres.yml` 使用以下环境变量：

- `DB_HOST`，默认 `localhost`
- `DB_PORT`，默认 `5432`
- `DB_NAME`，默认 `codebase_agent`
- `DB_USERNAME`，默认 `postgres`
- `DB_PASSWORD`，默认 `postgres`

核心配置等价于：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:codebase_agent}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
```

### 5.4 启动后端 postgres profile

PowerShell 示例：

```powershell
$env:DB_HOST="localhost"
$env:DB_PORT="5432"
$env:DB_NAME="codebase_agent"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="postgres"
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

如果你使用的是 Docker Compose 默认参数，也可以直接依赖默认值，仅执行：

```powershell
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

### 5.5 Flyway 行为

当前 `postgres` profile 不再依赖 `ddl-auto=update` 自动改表，而是使用：

- Flyway 负责初始化和版本化管理表结构
- Hibernate 使用 `validate` 校验实体与表结构是否一致

当前 migration 目录：

```text
src/main/resources/db/migration
```

当前初始化脚本：

```text
V1__init_core_tables.sql
```

为兼容已经用旧版本 `ddl-auto=update` 跑起来的 PostgreSQL 库，当前同时开启了：

```text
spring.flyway.baseline-on-migrate=true
```

这样：

- 新空库会正常执行 `V1`
- 已有非空库会先接管，再继续由 Flyway 管理

## 6. H2 和 PostgreSQL 的差异说明

### H2

- 启动快
- 配置简单
- 不需要额外容器或数据库服务
- 适合开发机临时调试

### PostgreSQL

- 更接近真实运行环境
- 表结构兼容性验证更可靠
- 数据重启后依然保留
- 更适合演示 Repo、CodeChunk、Task、Step、Artifact 的完整生命周期

面试时推荐这样解释：

```text
H2 只是为了降低本地启动门槛，用于单机快速调试。
正式运行或面试演示我会切到 PostgreSQL，因为它能更真实地验证表结构、索引、长文本字段和历史数据持久化行为。
```

## 7. 验收清单

切到 PostgreSQL profile 后，建议按下面顺序验收：

1. PostgreSQL 容器启动成功
2. 后端 `postgres` profile 启动成功
3. Clone Repo
4. Index Repo
5. Create Task
6. 查看 Agent Timeline
7. 查看 Artifact
8. 重启服务后历史数据仍存在

### 7.1 Docker 容器状态

```bash
docker ps
```

### 7.2 进入 PostgreSQL 查看表

```bash
docker exec -it codebase-agent-postgres psql -U postgres -d codebase_agent
```

查看所有表：

```sql
\dt
```

## 8. 常见问题

### 8.1 为什么 Navicat 能连上但浏览对象报错

如果 Navicat 版本较旧，可能对 PostgreSQL 15 的系统元数据兼容性不好。此时建议：

- 升级 Navicat
- 或直接用 `psql`
- 或换用 DBeaver / pgAdmin

### 8.2 为什么 artifact 没写入数据库

先确认不是数据库问题，再看任务是否已经真正执行到 `DeliveryAgent`。

如果 Agent 在中途失败，比如：

- LLM API key 未配置
- CodeUnderstanding / Diagnosis 阶段失败

那么 `agent_task` 和 `agent_task_step` 仍然会写入，但 `agent_artifact` 可能为空，因为任务没有走到最终交付。

## 9. 后续建议

当前版本在 PostgreSQL 上已经改为：

```text
spring.jpa.hibernate.ddl-auto=validate
```

H2 仍保持：

```text
spring.jpa.hibernate.ddl-auto=update
```

后续如果表结构继续演进，就在 `db/migration` 下按版本继续补 Flyway 脚本。

## 10. 运维与排障手册

如果需要：

- 查看 Flyway 是否执行成功
- 用 `psql` 检查表和历史数据
- 验证重启后数据是否仍存在
- 排查 Docker / Navicat / LLM / Artifact 落库问题

请直接参考：

- [Database Operations & Troubleshooting](./database-operations.md)

## 11. pgvector（可选增强，不是必选启动项）

当前项目已经把代码向量检索做成了 PostgreSQL 的可选增强层：

- 普通 PostgreSQL：继续可用，系统自动回退到 `code_chunk.embedding_json + 本地 cosine similarity`
- PostgreSQL + pgvector：额外启用 `code_chunk_embedding` 表，并在搜索时使用 pgvector 相似度查询

### 11.1 启动普通 PostgreSQL

```bash
docker compose -f docker-compose.postgres.yml up -d
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

### 11.2 启动带 pgvector 的 PostgreSQL

```bash
docker compose -f docker-compose.pgvector.yml up -d
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

### 11.3 pgvector 接入了什么

- Flyway `V2__init_pgvector_chunk_embeddings.sql`
- `code_chunk_embedding` 表
- repo index 时 embedding 双写：
  - `code_chunk.embedding_json`
  - `code_chunk_embedding.embedding`
- Search 支持三种模式：
  - `KEYWORD_ONLY`
  - `VECTOR_ONLY`
  - `HYBRID`

### 11.4 为什么把 pgvector 设计成可选

这样做是为了兼容两种环境：

1. 只想快速启动 PostgreSQL，不额外准备 pgvector 扩展
2. 希望做更真实的向量检索演示，启用 pgvector 镜像

也就是说，pgvector 是增强能力，不是项目启动前置条件。
