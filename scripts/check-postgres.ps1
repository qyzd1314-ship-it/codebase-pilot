param(
    [string]$ContainerName = "codebase-agent-postgres",
    [string]$DbUsername = "postgres",
    [string]$DbName = "codebase_agent",
    [switch]$ShowArtifacts,
    [switch]$ShowTasks
)

$ErrorActionPreference = "Stop"

Write-Host "[INFO] Checking Docker container status..." -ForegroundColor Cyan
docker ps --filter "name=$ContainerName"

Write-Host "[INFO] Checking tables in PostgreSQL..." -ForegroundColor Cyan
docker exec -i $ContainerName psql -U $DbUsername -d $DbName -c "\dt"

if ($ShowTasks) {
    Write-Host "[INFO] Showing latest tasks..." -ForegroundColor Cyan
    docker exec -i $ContainerName psql -U $DbUsername -d $DbName -c "select id, task_no, status, repo_id, business_type, created_at from agent_task order by id desc limit 10;"
}

if ($ShowArtifacts) {
    Write-Host "[INFO] Showing latest artifacts..." -ForegroundColor Cyan
    docker exec -i $ContainerName psql -U $DbUsername -d $DbName -c "select id, task_id, artifact_type, artifact_name, created_at from agent_artifact order by id desc limit 10;"
}
