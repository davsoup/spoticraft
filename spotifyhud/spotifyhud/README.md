# Spotify HUD (Fabric, Minecraft 26.3, client-only)

Shows what you're playing on Spotify in the corner of the screen and lets you control it in-game.

## Spotify setup (one time)
1. https://developer.spotify.com/dashboard -> Create app. Tick **Web API**.
2. Add this exact Redirect URI: `http://127.0.0.1:8888/callback`
3. Copy the app's **Client ID**.
4. Spotify requires the app owner to have **Premium** (Dev Mode rule), and volume/seek/skip control needs Premium anyway.

## Build
Needs JDK 25 (`java -version` must say 25).
Windows: `gradlew.bat build`   Mac/Linux: `./gradlew build`
Jar: `build/libs/spotifyhud-1.0.0.jar` -> drop in `mods/` with Fabric API.

## Use
Launch once (creates `config/spotifyhud.json`), put your Client ID in it, then in-game run `/spotify reload`
and press `;` (or `/spotify login`). Approve in the browser. The token is stored in `config/spotifyhud-token.json`.

| Key (rebindable) | Action |
|---|---|
| `;` | Log in |
| `\` | Play / pause |
| `[` / `]` | Previous / next track |
| `-` / `=` | Volume down / up (step in config) |
| `,` / `.` | Seek back / forward (10s default) |
| `'` | Show / hide HUD |

Commands: `/spotify volume <0-100>`, `/spotify seek <seconds>`, `/spotify next|previous|pause|login|reload`.

Notes: volume changes Spotify's own device volume (not Minecraft's). Something must be active in a Spotify app
(open it and press play once). Spotify refresh tokens expire after ~6 months; just log in again.
