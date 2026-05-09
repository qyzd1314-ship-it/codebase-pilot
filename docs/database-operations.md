# Database Operations & Troubleshooting

这份手册用于处理 PostgreSQL profile 的日常启动、排障、Flyway 检查和历史数据验证。

适用范围：

- Docker 启动 PostgreSQL
- Spring Boot `postgres` profile
- Flyway 管理表结构
- `psql` 命令行验证 Repo / Task / Artifact 历史数据

不涉及：

- Agent 主流程改造
- 前端改造
- pgvector
- Flyway 高级多版本演进策略

## 1. 快速启动

如果你不想手打一长串命令，也可以直接使用项目内脚本：

- [start-postgres.ps1](/D:/mycode/codebase-pilot/scripts/start-postgres.ps1)
- [start-backend-postgres.ps1](/D:/mycode/codebase-pilot/scripts/start-backend-postgres.ps1)
- [check-postgres.ps1](/D:/mycode/codebase-pilot/scripts/check-postgres.ps1)

### 1.1 启动 PostgreSQL 容器

```bash
docker compose -f docker-compose.postgres.yml up -d
```

或直接执行：

```powershell
.\scripts\start-postgres.ps1
```

### 1.2 检查容器状态

```bash
docker ps
```

正常应看到：

- 容器名：`codebase-agent-postgres`
- 端口映射：`5432->5432`

### 1.3 启动后端 postgres profile

PowerShell：

```powershell
$env:DB_HOST="localhost"
$env:DB_PORT="5432"
$env:DB_NAME="codebase_agent"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="postgres"
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

或直接执行：

```powershell
.\scripts\start-backend-postgres.ps1
```

如果要顺手带上 LLM：

```powershell
.\scripts\start-backend-postgres.ps1 -LlmEnabled true -DeepseekApiKey "你的真实key"
```

## 2. Flyway 运行机制

当前 PostgreSQL profile 使用：

- Flyway 初始化和管理 schema
- Hibernate `validate` 校验实体与表结构一致性

关键配置在：

- [application-postgres.yml](/D:/mycode/codebase-pilot/src/main/resources/application-postgres.yml)

当前 migration 目录：

- [db/migration](/D:/mycode/codebase-pilot/src/main/resources/db/migration)

当前版本脚本：

- [V1__init_core_tables.sql](/D:/mycode/codebase-pilot/src/main/resources/db/migration/V1__init_core_tables.sql)

## 3. 如何检查 Flyway 是否执行成功

### 3.1 看启动日志

正常日志应包含类似内容：

```text
Successfully validated 1 migration
Migrating schema "public" to version "1 - init core tables"
Successfully applied 1 migration
```

如果是旧库接管，可能会看到：

```text
Successfully baselined schema with version: 1
Schema "public" is up to date. No migration necessary.
```

这说明：

- 老库被 Flyway 接管成功
- 没有再重复建表

### 3.2 在 PostgreSQL 中查看 Flyway 记录

进入数据库：

```bash
docker exec -it codebase-agent-postgres psql -U postgres -d codebase_agent
```

查看 schema history：

```sql
select installed_rank, version, description, type, script, success
from flyway_schema_history
order by installed_rank;
```

## 4. 常用 psql 命令

### 4.1 进入数据库

```bash
docker exec -it codebase-agent-postgres psql -U postgres -d codebase_agent
```

如果只想快速检查状态，也可以直接执行：

```powershell
.\scripts\check-postgres.ps1
.\scripts\check-postgres.ps1 -ShowTasks -ShowArtifacts
```

### 4.2 查看所有表

```sql
\dt
```

### 4.3 查看表结构

```sql
\d repo
\d code_chunk
\d agent_task
\d agent_task_step
\d agent_artifact
```

### 4.4 退出 psql

```sql
\q
```

### 4.5 退出分页器

如果结果末尾显示 `(END)`，按：

```text
q
```

## 5. 历史数据验证命令

### 5.1 查看 Repo

```sql
select id, name, indexed_status, file_count, chunk_count, created_at
from repo
order by created_at desc;
```

### 5.2 查看 CodeChunk 数量

把 `repo_xxx` 换成真实 repoId：

```sql
select count(*) from code_chunk where repo_id = 'repo_xxx';
```

### 5.3 查看 Task

```sql
select id, task_no, status, repo_id, business_type, created_at
from agent_task
order by id desc
limit 10;
```

### 5.4 查看 Step Timeline

把 `1` 换成真实 taskId：

```sql
select id, task_id, step_seq, assigned_agent, status, step_type
from agent_task_step
where task_id = 1
order by step_seq;
```

### 5.5 查看 Artifact

把 `1` 换成真实 taskId：

```sql
select id, task_id, artifact_type, artifact_name, created_at
from agent_artifact
where task_id = 1
order by id;
```

### 5.6 查看最近的 Artifact

```sql
select id, task_id, artifact_type, artifact_name, created_at
from agent_artifact
order by id desc
limit 10;
```

## 6. 验证“重启后历史数据仍存在”

### 6.1 停止后端应用

直接关闭当前 Spring Boot 进程窗口，或在终端里 `Ctrl + C`。

### 6.2 重新启动后端

```powershell
$env:DB_HOST="localhost"
$env:DB_PORT="5432"
$env:DB_NAME="codebase_agent"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="postgres"
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

### 6.3 再查历史数据

进入 psql 后再次执行：

```sql
select id, name, indexed_status, file_count, chunk_count from repo order by created_at desc;
select id, task_id, artifact_type, artifact_name, created_at from agent_artifact order by id desc limit 10;
```

如果还能看到之前的 Repo / Artifact，说明历史数据保留正常。

## 7. 如何创建一个新的空数据库做 Flyway 验证

进入默认库：

```bash
docker exec -it codebase-agent-postgres psql -U postgres -d postgres
```

创建临时测试库：

```sql
drop database if exists codebase_agent_flyway_test;
create database codebase_agent_flyway_test;
```

然后用新的数据库名启动应用：

```powershell
$env:DB_NAME="codebase_agent_flyway_test"
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

正常情况下，Flyway 会在空库上直接执行 `V1`。

## 8. 常见问题排障

### 8.1 `Unknown lifecycle phase ".run.profiles=postgres"`

原因：

- Maven 参数在 PowerShell 里被错误解析了

解决：

```powershell
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

给 `-D...` 这段加引号。

### 8.2 Docker 拉不到 `postgres:15`

常见表现：

- `EOF`
- `failed to resolve reference`
- `registry-1.docker.io` 超时

排查方向：

1. 确认 Docker Desktop 已启动
2. 确认 Docker Desktop 代理配置正确
3. 再执行：

```bash
docker pull postgres:15
```

### 8.3 Navicat 能测试连接，但展开对象报错

常见表现：

- `column "datlastsysoid" does not exist`

原因：

- Navicat 版本较旧，对 PostgreSQL 15 的元数据查询不兼容

建议：

- 升级 Navicat
- 或直接用 `psql`
- 或换 DBeaver / pgAdmin

### 8.4 Artifact 没有写入数据库

先不要立刻怀疑数据库。

优先检查任务是否真正执行到了 `DeliveryAgent`：

```sql
select id, task_id, step_seq, assigned_agent, status, step_type
from agent_task_step
where task_id = 1
order by step_seq;
```

如果：

- `DiagnosisAgent` / `CodeUnderstandingAgent` 失败
- `ReviewerAgent` 或 `DeliveryAgent` 还是 `PENDING`

那么 `agent_artifact` 为空通常是业务链路中断，不是 PostgreSQL 写入失败。

### 8.5 LLM unavailable / API key not configured

常见表现：

- `LLM is unavailable`
- `LLM is disabled or the provider API key is not configured`

解决：

在启动后端前设置：

```powershell
$env:LLM_ENABLED="true"
$env:DEEPSEEK_API_KEY="你的真实key"
```

然后再启动：

```powershell
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

### 8.6 Flyway 接管旧库时看到 baseline 日志

这是正常现象。

如果旧库之前已经由 `ddl-auto=update` 建好表，当前配置：

```text
spring.flyway.baseline-on-migrate=true
```

会让 Flyway 先接管已有 schema，再继续管理版本。

### 8.7 Hibernate validate 失败

常见原因：

- 实体字段变了，但 migration 没更新
- 表结构是旧版手工改出来的
- 新增索引或列后没有补 Flyway 脚本

处理建议：

1. 先看报错指出的是哪张表、哪一列
2. 对照实体类和 `db/migration` 脚本
3. 新增下一版 migration，不要回滚修改已上线版本脚本

## 9. 运维建议

### 9.1 本地开发

- `h2` 继续用于快速调试
- 不用每次都起 PostgreSQL

### 9.2 面试演示

- 优先用 `postgres`
- 演示前提前跑一次：
  - Clone Repo
  - Index Repo
  - Create Task
  - 确认 Artifact 已落库

### 9.3 后续 schema 演进

以后每次改表结构，建议：

1. 不要改 `V1__init_core_tables.sql` 已上线含义
2. 新增下一个版本，例如：

```text
V2__add_xxx.sql
V3__alter_xxx.sql
```

3. 保持实体与 migration 一起演进
