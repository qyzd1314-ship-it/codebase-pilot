# pgvector 接入与验证手册

## 1. 目标

本轮改造的目标是把当前项目从：

- 关键词检索
- 本地 embedding fallback
- 内存 cosine similarity

升级为：

- PostgreSQL 持久化 `CodeChunk`
- pgvector 持久化 `CodeChunk embedding`
- `KEYWORD_ONLY / VECTOR_ONLY / HYBRID` 三种搜索模式

同时保持：

- 不改 Agent 主流程
- 不改前端
- pgvector 作为可选增强，不是必须启动项

## 2. 本轮接入内容

### 2.1 新增向量表

Flyway `V2` 会在 pgvector 可用时创建：

- `code_chunk_embedding`

表结构核心字段：

- `id`
- `repo_id`
- `chunk_id`
- `embedding_model`
- `embedding`
- `content_hash`
- `created_at`

### 2.2 向量写入时机

在 Repo Index 完成 `code_chunk` 落库后：

- 保留原有 `code_chunk.embedding_json`
- 如果 PostgreSQL 已启用 `vector` 扩展，再额外写入 `code_chunk_embedding`

### 2.3 搜索模式

当前搜索支持三种模式：

- `KEYWORD_ONLY`
- `VECTOR_ONLY`
- `HYBRID`

其中 `HYBRID` 使用融合公式：

```text
keywordScore * 0.6 + vectorScore * 0.4 + pathBoost + chunkTypeBoost
```

## 3. 为什么保留关键词检索

代码检索和纯自然语言文档检索不一样。

像下面这些信号：

- 类名
- 方法名
- 异常名
- 接口路径
- 配置键名

往往是强结构化关键词，单纯向量检索不一定稳定命中。

所以当前设计不是“向量替代关键词”，而是：

```text
关键词检索负责强符号命中
+ 向量检索负责语义召回
+ Hybrid 负责融合排序
```

## 4. 启动方式

### 4.1 普通 PostgreSQL

适合：

- 本地持久化验证
- 不需要演示向量检索
- 只想验证 Repo / Task / Artifact 等核心链路

启动：

```bash
docker compose -f docker-compose.postgres.yml up -d
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

### 4.2 PostgreSQL + pgvector

适合：

- 演示向量检索
- 验证 `code_chunk_embedding`
- 演示 Hybrid Search

启动：

```bash
docker compose -f docker-compose.pgvector.yml up -d
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

## 5. 验证步骤

### 5.1 启动 pgvector 容器

```bash
docker compose -f docker-compose.pgvector.yml up -d
docker ps
```

正常应看到：

- `codebase-agent-pgvector`
- `0.0.0.0:5432->5432/tcp`

### 5.2 进入 PostgreSQL

```bash
docker exec -it codebase-agent-pgvector psql -U postgres -d codebase_agent
```

### 5.3 检查扩展能力

查询扩展包是否可用：

```sql
select name from pg_available_extensions where name = 'vector';
```

如有需要，手动启用：

```sql
create extension if not exists vector;
```

确认扩展已启用：

```sql
select extname from pg_extension where extname = 'vector';
```

### 5.4 检查 Flyway 建表结果

查看表：

```sql
\dt
```

应能看到：

- `flyway_schema_history`
- `repo`
- `code_chunk`
- `agent_task`
- `agent_task_step`
- `agent_artifact`
- `code_chunk_embedding`

查看向量表结构：

```sql
\d code_chunk_embedding
```

### 5.5 创建并索引 Repo

创建 repo：

```powershell
$body = @{
  url = "https://github.com/spring-guides/gs-rest-service.git"
  branch = "main"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8123/api/repos" `
  -ContentType "application/json" `
  -Body $body
```

索引 repo：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8123/api/repos/{repoId}/index"
```

### 5.6 验证 embedding 已写入

```sql
select count(*) from code_chunk_embedding where repo_id = '{repoId}';
```

如果返回大于 0，说明向量写入成功。

## 6. 本轮实机验证结果

本轮在 `repo_9597f937054a4011` 上完成了实机验证。

### 6.1 核心表与向量表

`psql` 中可见：

- `repo`
- `code_chunk`
- `agent_task`
- `agent_task_step`
- `agent_artifact`
- `code_chunk_embedding`

### 6.2 embedding 落库

执行：

```sql
select count(*) from code_chunk_embedding where repo_id = 'repo_9597f937054a4011';
```

结果：

```text
27
```

说明该 repo 的 chunk embedding 已成功写入 PostgreSQL。

### 6.3 VECTOR_ONLY 查询通过

请求：

```powershell
$searchBody = @{
  query = "greeting endpoint request param name validation"
  topK = 5
  searchMode = "VECTOR_ONLY"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8123/api/repos/repo_9597f937054a4011/search" `
  -ContentType "application/json" `
  -Body $searchBody
```

返回：

- 有 `results`
- 命中了 `GreetingController.java` 相关 chunk

说明：

- query embedding 已生成
- pgvector 相似度检索已生效

### 6.4 HYBRID 查询通过

请求：

```powershell
$searchBody = @{
  query = "greeting endpoint request param name validation"
  topK = 5
  searchMode = "HYBRID"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8123/api/repos/repo_9597f937054a4011/search" `
  -ContentType "application/json" `
  -Body $searchBody
```

返回：

- 有 `results`
- 能继续返回 `chunkId / filePath / score / reason`

说明：

- Hybrid Search 已启用
- 现有 `EvidenceRef` 结构未被破坏

## 7. 当前结论

本轮已经完成：

1. `pgvector` 扩展接入
2. `code_chunk_embedding` 表初始化
3. Repo Index 时 embedding 持久化
4. `VECTOR_ONLY` 搜索可用
5. `HYBRID` 搜索可用
6. `CODE_UNDERSTANDING / BUG_DIAGNOSIS` 主流程未受破坏

当前检索层能力可描述为：

```text
关键词召回 + pgvector 语义召回 + 融合排序
```

## 8. 面试表达

可以这样介绍：

```text
最初版本我先做了关键词检索和本地 embedding fallback，保证代码符号、异常名、接口路径等强特征可命中。

在 PostgreSQL profile 稳定后，我又把 CodeChunk 的 embedding 持久化接入 pgvector，新增 code_chunk_embedding 表，并支持 Keyword-only、Vector-only 和 Hybrid 三种搜索模式。

这样做的好处是，不需要重写 Agent 主流程，现有 CodeSearchAgent 仍然走统一搜索接口，但底层已经可以根据运行环境切换到 PostgreSQL 向量检索，并继续输出 EvidenceRef 给 Diagnosis 和 Reviewer 使用。
```

## 9. 后续优化建议

当前是最小可运行版本，后续可以继续优化：

1. 为 `code_chunk_embedding.embedding` 增加更高阶的向量索引策略
2. 引入更真实的 embedding 模型，而不是本地 hash embedding fallback
3. 增加向量写入去重和增量更新策略
4. 为 Eval 面板补充 `KEYWORD_ONLY / VECTOR_ONLY / HYBRID` 对比展示
5. 在前端增加搜索模式切换和调试信息展示
