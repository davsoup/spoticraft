# SpotiCraft Overlay

The Spotify HUD from the Minecraft mod, as a standalone Windows exe: a transparent, always-on-top, click-through overlay over your whole screen.

Download `SpotiCraftOverlay.exe` from the latest `overlay-v*` release. Windows 10/11, nothing to install (.NET Framework 4.8 is built in). It's unsigned, so SmartScreen may ask: More info → Run anyway.

## First run

1. Run the exe. It sits in the tray.
2. Press **P** (or click the tray icon) → **Set Spotify clientId**. Use the same Client ID as the mod; the redirect URI is the same (`http://127.0.0.1:8888/callback`).
3. Log in in the browser. Done; the token is stored encrypted for your Windows user.

If `%APPDATA%\.minecraft\config\spotifyhud.json` exists, the first run imports its look, beat links and Client ID.

## Controls

**P** toggles interactive mode (the screen dims):

- drag the card to move it, drag a corner to resize
- click/drag the progress bar to seek, the volume bar to set volume, scroll on the card for volume
- Space play/pause, ←/→ seek, ↑/↓ volume, Esc or P to close
- buttons: shuffle / prev / play / next / repeat, HUD on/off, beat glow, glow, audio source, background

Bare **P** is a global hotkey, so it can't be typed in other apps while the overlay runs. Change `hotkey` in the settings (e.g. `"Alt+P"`, `"Ctrl+Shift+F9"`) or untick it in the tray menu.

## Beat-reactive glow

**Beat glow** captures audio from the Spotify desktop app only (Windows 10 2004+ process loopback) or all PC audio, in-process, nothing recorded. First enable adds two starter links: glow intensity pumps on the beat, glow spread follows the bass.

Every setting can be beat-linked in `config.json` (`links`, same format as the mod). The Customize window that edits them comes in a later stage.

## Settings

`%APPDATA%\SpotiCraftOverlay\config.json` (tray → Open settings file, then tray → Reload settings). Same field names as the mod, plus:

| key | default | |
|---|---|---|
| `hotkey` | `"P"` | modifier names: Ctrl, Alt, Shift, Win |
| `hotkeyEnabled` | `true` | |
| `uiScale` | `1.75` | screen pixels per HUD unit, multiplied by Windows DPI scaling |
| `hideWhenIdle` | `true` | hide when nothing is playing |
| `fps` | `60` | |

`hudX`/`hudY` are screen pixels here.

## Notes

- Shows over borderless/windowed games, not exclusive fullscreen.
- Primary monitor only for now.

## Stages

1. **Core HUD, controls, beat-reactive glow** ← this version
2. CD + vinyl styles
3. Lyrics: karaoke, line flash, smooth slide
4. Queue peek
5. Ambient screen-edge glow
6. Customize window with beat links on every setting

## Build

```
dotnet build SpotiCraftOverlay.csproj -c Release -o out
dotnet build tests/Tests.csproj -c Release -o testbin && testbin/Tests.exe renders
```

CI (`.github/workflows/main.yml`, job `overlay`) builds on Windows, runs the tests and publishes the exe as release `overlay-v<Version>`.
