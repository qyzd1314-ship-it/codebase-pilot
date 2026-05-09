param(
    [string]$DbHost = "localhost",
    [string]$DbPort = "5432",
    [string]$DbName = "codebase_agent",
    [string]$DbUsername = "postgres",
    [string]$DbPassword = "postgres",
    [string]$LlmEnabled = "",
    [string]$DeepseekApiKey = "",
    [string]$DashscopeApiKey = ""
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

$env:DB_HOST = $DbHost
$env:DB_PORT = $DbPort
$env:DB_NAME = $DbName
$env:DB_USERNAME = $DbUsername
$env:DB_PASSWORD = $DbPassword

if ($LlmEnabled -ne "") {
    $env:LLM_ENABLED = $LlmEnabled
}

if ($DeepseekApiKey -ne "") {
    $env:DEEPSEEK_API_KEY = $DeepseekApiKey
}

if ($DashscopeApiKey -ne "") {
    $env:DASHSCOPE_API_KEY = $DashscopeApiKey
}

Write-Host "[INFO] Starting backend with postgres profile..." -ForegroundColor Cyan
Write-Host "[INFO] DB_HOST=$($env:DB_HOST), DB_PORT=$($env:DB_PORT), DB_NAME=$($env:DB_NAME), DB_USERNAME=$($env:DB_USERNAME)" -ForegroundColor DarkGray

& ".\mvnw.cmd" spring-boot:run "-Dspring-boot.run.profiles=postgres"
