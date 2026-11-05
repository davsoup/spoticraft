param([int]$Mode = 0, [int]$ParentPid = 0)
$ErrorActionPreference = 'Stop'
try {
    $src = Get-Content -Raw -Path (Join-Path $PSScriptRoot 'beat_helper.cs')
    Add-Type -TypeDefinition $src -Language CSharp
    [SpotifyHudBeat]::Run($Mode, $ParentPid)
} catch {
    [Console]::Out.WriteLine('# Helper failed: ' + $_.Exception.Message.Replace("`n", ' '))
}
