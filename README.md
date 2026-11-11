# android-control

An MCP server that lets Claude or ChatGPT operate an Android phone: see the
screen, tap, swipe, type, press keys and open apps. It runs on the PC and
drives the phone over adb.

## Connect the phone

1. On the phone: Settings > About phone > tap "Build number" 7 times.
2. Settings > Developer options > turn on "USB debugging".
3. Plug the phone into the PC and accept the "Allow USB debugging" prompt.
4. Check it shows up: `adb devices`

Without a cable (same Wi-Fi): with the cable in once, run `adb tcpip 5555`,
unplug, then `adb connect <phone-ip>:5555`.

## Use it

- **Claude desktop / Claude Code**: already registered as `android-control`.
  Restart the app, then ask e.g. "open YouTube on my phone and search for lo-fi".
- **ChatGPT, Claude phone app, claude.ai**: these need a public HTTPS address.
  Run `python server.py --http`, put a tunnel (Cloudflare Tunnel, ngrok,
  Tailscale Funnel) in front of `127.0.0.1:8765`, and add
  `https://<tunnel-host>/<remote_key>/mcp` as a custom connector. The PC must
  stay on with the phone connected.

## Tools

`phone_info`, `screenshot`, `read_screen`, `tap`, `long_press`, `swipe`,
`type_text`, `press_key`, `open_app`, `list_apps`, `open_url`, `wait`.
Coordinates are pixels in the latest screenshot.

## Safety

- `config.json` holds `blocked_keywords` and `blocked_packages`. While a
  matching app (banking, UPI, wallets) is in front, taps and typing are
  refused. Edit the lists to suit your phone.
- `remote_key` in `config.json` is the secret part of the HTTP address. Anyone
  who has it can drive the phone, so keep it private. Delete it to get a new one.
- Every action is appended to `actions.log`.
- Typing is ASCII only (an adb limit).
