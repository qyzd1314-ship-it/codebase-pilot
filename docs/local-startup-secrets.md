# Local Startup Secrets

This project no longer stores real API keys in committed configuration.

## 如何启用 LLM

当前统一 LLM 封装默认是可选能力：

- `LLM_ENABLED=false`：应用可以正常启动，但 `/api/llm/test` 会返回 `LLM is disabled or DASHSCOPE_API_KEY is not configured`
- `LLM_ENABLED=true` 且配置了 `DASHSCOPE_API_KEY`：可以正常调用 DashScope / Qwen 模型

## 需要的环境变量

- `LLM_ENABLED`
- `DASHSCOPE_API_KEY`
- `SEARCH_API_KEY`（只有联网搜索功能开启时才需要）

## PowerShell 示例

```powershell
$env:LLM_ENABLED="true"
$env:DASHSCOPE_API_KEY="your-dashscope-api-key"
$env:SEARCH_API_KEY="your-search-api-key"
.\mvnw spring-boot:run
```

如果你只想启动非 LLM 功能：

```powershell
$env:LLM_ENABLED="false"
.\mvnw spring-boot:run
```

## Bash 示例

```bash
export LLM_ENABLED="true"
export DASHSCOPE_API_KEY="your-dashscope-api-key"
export SEARCH_API_KEY="your-search-api-key"
./mvnw spring-boot:run
```

如果你只想启动非 LLM 功能：

```bash
export LLM_ENABLED="false"
./mvnw spring-boot:run
```

## 不设置 Key 时哪些功能可用

可用：

- Repo 管理
- 代码扫描与 Chunk
- 代码搜索
- Task / Evidence / Artifact
- Eval

不可用或会降级：

- `/api/llm/test`
- 直接依赖 DashScope 模型的旧 AI 对话能力
- 后续接入 `LlmService` 的 Diagnosis / Review / Patch 能力

## Optional local override file

You can copy `src/main/resources/application-local.example.yml` to `application-local.yml` for local reference,
but do not commit `application-local.yml` to Git.

## Security note

Do not place real API keys in `application.yml`, `README.md`, `.env.example`, or any committed file.
