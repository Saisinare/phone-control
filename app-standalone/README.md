# Phone Control (standalone)

The same 12 tools as `../server.py`, served from the phone itself. No PC needed once it is installed.

## Build and install

```
gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`gradle.properties` points Gradle at the JDK bundled with Android Studio.

## Set up on the phone

1. Open **Phone Control**, tap **Open accessibility settings** and turn the service on.
   On Android 13+ a sideloaded app is greyed out there at first: open the app's
   App info page, tap the three dots, choose **Allow restricted settings**, then try again.
2. Turn on the **Server** switch. A notification with a **Stop** button stays while it runs.
3. Give the MCP client the address shown (`http://127.0.0.1:8765/<secret>/mcp`).
   From a PC over USB: `adb forward tcp:8765 tcp:8765` and use the same address.

The server listens only on the phone itself unless **Also allow devices on the same Wi-Fi** is ticked.

## Public address for Claude / ChatGPT (tunnel on the phone)

Claude and ChatGPT call connectors from their own servers, so they need a public https address.
Termux (official GitHub build) with `cloudflared` provides one, all on the phone:

1. Turn on the **Server** switch in Phone Control.
2. Open **Termux** and type `tunnel`. It prints the full address to give the AI app.
3. Leave Termux running. Ctrl+C (or closing Termux) stops the tunnel.

The address is new on every start. `termux/tunnel` is the script (installed at `$PREFIX/bin/tunnel`);
it reads the secret part from `~/.phonecontrol_secret` in Termux, so after **New secret** in the app
put the new secret in that file. Anyone who has the full address can operate the phone.

## Differences from the adb server

- `type_text` takes any language and emoji. It inserts at the cursor; in a password field it replaces the contents.
- `open_app` matches app names as well as package names.
- `press_key`: `power` locks the screen; `menu` and `search` are not available; `up/down/left/right` need Android 13+.
- Taps, typing and swipes are also refused while Phone Control's own screen is in front, so a
  connected app cannot edit the protected list or the secret.
- `uiautomator dump` (used by the adb server) switches accessibility services off for a moment,
  so do not run both against the same phone at once.

## Layout

- `ControlService.kt` accessibility service: screenshot, gestures, text, keys, UI tree
- `Tools.kt` the 12 tools and the protected-app guard
- `McpServer.kt` HTTP server speaking MCP Streamable HTTP (JSON replies)
- `ServerService.kt` foreground service and notification
- `MainActivity.kt` the one screen
- `Config.kt` settings, protected list, action log
