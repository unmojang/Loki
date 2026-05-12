# Installation

## Clients

Loki is supported on the following clients:

- **[Fjord Launcher](https://github.com/unmojang/FjordLauncher)**

## Servers

To simplify Loki's install process onto the Minecraft Server, you may periodically run this script to download/upgrade Loki:

- UNIX-like OSes (Linux, BSDs, etc.):
  ```
  #!/usr/bin/env bash
  set -euo pipefail

  DEST="/opt/Loki.jar"  # adjust as necessary

  json=$(curl -s https://api.github.com/repos/unmojang/Loki/releases/latest)

  tag=$(echo "$json" | jq -rcM .tag_name)
  download_url=$(echo "$json" \
    | jq -rcM .assets[0].browser_download_url)

  curl -L "$download_url" -o "$DEST"

  echo "Downloaded Loki ${tag#v} to $DEST"
  ```

- Windows (PowerShell)
  ```
  $Dest = "C:\ProgramData\Loki.jar"  # adjust as necessary

  $json = Invoke-RestMethod -Uri "https://api.github.com/repos/unmojang/Loki/releases/latest"

  $tag = $json.tag_name
  $downloadUrl = $json.assets[0].browser_download_url

  Invoke-WebRequest -Uri $downloadUrl -OutFile $Dest

  Write-Host "Downloaded Loki $($tag -replace '^v') to $Dest"
  ```