# One-command production deploy for Dandelion and Bob's Adventure.
#
#   .\deploy\deploy.ps1 dandelion                        # build, upload, deploy
#   .\deploy\deploy.ps1 dandelion -Migration v5.15       # also back up the DB and apply src\db\v5.15.sql
#   .\deploy\deploy.ps1 dandelion -SkipBuild             # deploy the WAR already in target\
#   .\deploy\deploy.ps1 bobs-adventure                   # build and deploy the game
#   .\deploy\deploy.ps1 dandelion -StageOnly             # upload only; deploy later
#
# Uses the Windows OpenSSH client, which has the deploy key (Git Bash's scp does not).
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [ValidateSet('dandelion', 'bobs-adventure')]
  [string]$App,
  [string]$Migration,
  [switch]$SkipBuild,
  [switch]$StageOnly
)
$ErrorActionPreference = 'Stop'
$ssh = 'C:\WINDOWS\System32\OpenSSH\ssh.exe'
$scp = 'C:\WINDOWS\System32\OpenSSH\scp.exe'
$server = 'deploy@dandelion-daily.org'
$dandelionRoot = Split-Path $PSScriptRoot -Parent
$bobsRoot = Join-Path (Split-Path $dandelionRoot -Parent) 'bobs-adventure'

function Invoke-Checked([string]$what, [scriptblock]$block) {
  & $block
  if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

function Warn-IfDirty([string]$repo) {
  $dirty = git -C $repo status --porcelain
  if ($dirty) { Write-Host "WARNING: $repo has uncommitted changes; they are included in this build." -ForegroundColor Yellow }
}

if ($App -eq 'dandelion') {
  $war = Join-Path $dandelionRoot 'target\dandelion.war'
  $remoteArg = ''
  if ($Migration) {
    $name = $Migration -replace '\.sql$', ''
    $sql = Join-Path $dandelionRoot "src\db\$name.sql"
    if (-not (Test-Path $sql)) { throw "Migration not found: $sql" }
  }
  $version = ([xml](Get-Content (Join-Path $dandelionRoot 'pom.xml'))).project.version
  Warn-IfDirty $dandelionRoot
  if (-not $SkipBuild) {
    Write-Host "Building Dandelion $version..." -ForegroundColor Cyan
    Push-Location $dandelionRoot
    try { Invoke-Checked 'mvn clean package' { mvn -q clean package } } finally { Pop-Location }
  }
  if (-not (Test-Path $war)) { throw "No WAR at $war" }
  Write-Host "Uploading dandelion.war ($version)..." -ForegroundColor Cyan
  Invoke-Checked 'scp' { & $scp $war "${server}:/home/deploy/dandelion.war" }
  if ($Migration) {
    Write-Host "Uploading $name.sql..." -ForegroundColor Cyan
    Invoke-Checked 'scp' { & $scp $sql "${server}:/home/deploy/migrations/$name.sql" }
    $remoteArg = " $name.sql"
  }
  $remote = "sudo deploy-dandelion$remoteArg"
}
else {
  $war = Join-Path $bobsRoot 'dist\bobs-adventure.war'
  Warn-IfDirty $bobsRoot
  if (-not $SkipBuild) {
    Write-Host "Building Bob's Adventure..." -ForegroundColor Cyan
    Invoke-Checked 'build-war.ps1' { powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $bobsRoot 'build-war.ps1') }
  }
  if (-not (Test-Path $war)) { throw "No WAR at $war" }
  Write-Host "Uploading bobs-adventure.war..." -ForegroundColor Cyan
  Invoke-Checked 'scp' { & $scp $war "${server}:/home/deploy/bobs-adventure.war" }
  $remote = 'sudo deploy-bobs-adventure'
}

if ($StageOnly) {
  Write-Host "Staged. Deploy later with: ssh $server `"$remote`"" -ForegroundColor Green
  exit 0
}

Write-Host "Deploying on the server..." -ForegroundColor Cyan
Invoke-Checked 'remote deploy' { & $ssh -t $server $remote }
Write-Host "Done." -ForegroundColor Green
