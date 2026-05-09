param(
    [string]$ComposeFile = "docker-compose.postgres.yml"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$composePath = Join-Path $projectRoot $ComposeFile

if (-not (Test-Path $composePath)) {
    throw "Compose file not found: $composePath"
}

Write-Host "[INFO] Starting PostgreSQL container with compose file: $composePath" -ForegroundColor Cyan
docker compose -f $composePath up -d

Write-Host "[INFO] Current container status:" -ForegroundColor Cyan
docker ps --filter "name=codebase-agent-postgres"
