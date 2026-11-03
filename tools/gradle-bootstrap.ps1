param([Parameter(ValueFromRemainingArguments=$true)][string[]]$GradleArguments)
$projectDirectory = Split-Path -Parent $PSScriptRoot
$toolDirectory = Join-Path $projectDirectory '.toolchain'
$gradleExecutable = Join-Path $toolDirectory 'gradle-8.13\bin\gradle.bat'
try {
    if (!(Test-Path $gradleExecutable)) {
        New-Item -ItemType Directory -Force -Path $toolDirectory -ErrorAction Stop | Out-Null
        $archivePath = Join-Path $toolDirectory 'gradle-8.13-bin.zip'
        $sourceUrl = 'https://services.gradle.org/distributions/gradle-8.13-bin.zip'
        Invoke-WebRequest -Uri $sourceUrl -OutFile $archivePath -TimeoutSec 300 -ErrorAction Stop
        $expectedHash = (Invoke-WebRequest -Uri "$sourceUrl.sha256" -TimeoutSec 30 -ErrorAction Stop).Content.Trim()
        $actualHash = (Get-FileHash -Algorithm SHA256 -Path $archivePath -ErrorAction Stop).Hash
        if ($actualHash -ne $expectedHash) { throw 'Gradle download checksum mismatch' }
        Expand-Archive -Path $archivePath -DestinationPath $toolDirectory -Force -ErrorAction Stop
    }
    & $gradleExecutable -p $projectDirectory @GradleArguments
    exit $LASTEXITCODE
} catch {
    Write-Error $_
    exit 1
}
