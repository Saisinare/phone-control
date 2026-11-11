"""Android phone control MCP server.

Drives an Android phone over adb so an MCP client (Claude, ChatGPT) can see
the screen and tap, swipe, type and open apps, the same way computer-use
tools drive a PC.

  python server.py                 stdio (Claude desktop / Claude Code)
  python server.py --http          HTTP on 127.0.0.1:8765 behind a secret path
"""

import argparse
import io
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

from mcp.server.fastmcp import FastMCP, Image
from mcp.server.transport_security import TransportSecuritySettings
from PIL import Image as PILImage

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"
LOG_PATH = HERE / "actions.log"

DEFAULT_CONFIG = {
    # adb serial to use; empty picks the first connected device.
    "device": "",
    # Screenshots are scaled so the long side is at most this many pixels.
    # All tool coordinates are in screenshot pixels.
    "max_screenshot_side": 1344,
    # Taps, typing and app launches are refused while one of these is in front.
    "blocked_keywords": ["bank", "upi", "wallet", "paytm", "phonepe", "payment"],
    "blocked_packages": [
        "com.google.android.apps.nbu.paisa.user",
        "com.dreamplug.androidapp",
        "com.snapwork.hdfc",
        "com.csam.icici.bank.imobile",
        "com.sbi.lotusintouch",
        "com.sbi.SBIFreedomPlus",
        "com.axis.mobile",
        "com.msf.kbank.mobile",
        "com.mobikwik_new",
    ],
    # Secret path segment for --http mode; generated on first use.
    "remote_key": "",
}


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_PATH.exists():
        cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
    else:
        CONFIG_PATH.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    return cfg


CONFIG = load_config()


# ---------------------------------------------------------------- adb plumbing

def _find_adb() -> str:
    found = os.environ.get("ADB_PATH") or shutil.which("adb")
    if found:
        return found
    guess = Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"
    if guess.exists():
        return str(guess)
    raise RuntimeError("adb not found. Install Android platform-tools or set ADB_PATH.")


ADB = _find_adb()
_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _run(cmd: list[str], timeout: int = 30) -> bytes:
    # stdin must be detached: in stdio mode it carries the MCP stream.
    r = subprocess.run(
        cmd, capture_output=True, stdin=subprocess.DEVNULL,
        timeout=timeout, creationflags=_NO_WINDOW,
    )
    if r.returncode != 0:
        msg = (r.stderr or r.stdout).decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"adb failed: {msg or 'exit ' + str(r.returncode)}")
    return r.stdout


def _serial() -> str:
    wanted = os.environ.get("ANDROID_SERIAL") or CONFIG.get("device") or ""
    out = _run([ADB, "devices"]).decode("utf-8", errors="replace")
    ready = [ln.split("\t")[0] for ln in out.splitlines()[1:] if ln.endswith("\tdevice")]
    if wanted:
        if wanted not in ready:
            raise RuntimeError(f"Device {wanted} is not connected. Connected: {ready or 'none'}")
        return wanted
    if not ready:
        raise RuntimeError(
            "No phone connected. Turn on USB debugging and plug it in, "
            "or pair over Wi-Fi with `adb connect <phone-ip>:5555`."
        )
    return ready[0]


def adb(*args: str, timeout: int = 30) -> str:
    return _run([ADB, "-s", _serial(), *args], timeout).decode("utf-8", errors="replace")


def adb_bytes(*args: str, timeout: int = 30) -> bytes:
    return _run([ADB, "-s", _serial(), *args], timeout)


def sh(command: str, timeout: int = 30) -> str:
    """Run one command line in the phone's shell."""
    return adb("shell", command, timeout=timeout)


def _quote(s: str) -> str:
    return "'" + s.replace("'", "'\\''") + "'"


# ------------------------------------------------------------ screen geometry

def _device_size() -> tuple[int, int]:
    sizes = re.findall(r"(\d+)x(\d+)", sh("wm size"))
    if not sizes:
        raise RuntimeError("Could not read the screen size.")
    w, h = sizes[-1]  # an override size, when present, is listed last
    return int(w), int(h)


def _scale() -> float:
    """Device pixels per screenshot pixel (same on both axes, any rotation)."""
    long_side = max(_device_size())
    return max(1.0, long_side / float(CONFIG["max_screenshot_side"]))


def _to_device(x: float, y: float) -> tuple[int, int]:
    s = _scale()
    return int(round(x * s)), int(round(y * s))


# --------------------------------------------------------------- safety + log

def _foreground() -> str:
    out = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true")
    m = re.search(r"([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/", out)
    return m.group(1) if m else ""


def _is_blocked(package: str) -> bool:
    p = package.lower()
    if not p:
        return False
    if any(p == b.lower() for b in CONFIG["blocked_packages"]):
        return True
    return any(k.lower() in p for k in CONFIG["blocked_keywords"])


def _guard() -> None:
    pkg = _foreground()
    if _is_blocked(pkg):
        raise RuntimeError(
            f"Refused: {pkg} is on the protected list (banking and payments). "
            "The owner has to do this part by hand. Use press_key('home') to leave."
        )


def _log(action: str, **details) -> None:
    entry = {"time": datetime.now().isoformat(timespec="seconds"), "action": action, **details}
    with LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


# ----------------------------------------------------------------------- tools

mcp = FastMCP(
    "android-control",
    instructions=(
        "Controls the user's Android phone. Start with screenshot or read_screen, "
        "then act with tap / swipe / type_text / press_key / open_app, and look "
        "again after each action. All coordinates are pixels in the most recent "
        "screenshot. Banking and payment apps are blocked on purpose."
    ),
)


@mcp.tool()
def phone_info() -> str:
    """Phone model, Android version, screen size and the app currently in front."""
    w, h = _device_size()
    s = _scale()
    return json.dumps({
        "model": sh("getprop ro.product.model").strip(),
        "android": sh("getprop ro.build.version.release").strip(),
        "device_pixels": f"{w}x{h}",
        "screenshot_pixels": f"{round(w / s)}x{round(h / s)}",
        "foreground_app": _foreground(),
    }, indent=2)


@mcp.tool()
def screenshot():
    """Capture the phone screen. Coordinates for tap and swipe refer to this image."""
    png = adb_bytes("exec-out", "screencap", "-p")
    img = PILImage.open(io.BytesIO(png)).convert("RGB")
    s = max(1.0, max(img.size) / float(CONFIG["max_screenshot_side"]))
    if s > 1.0:
        img = img.resize((round(img.width / s), round(img.height / s)), PILImage.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=80)
    note = f"{img.width}x{img.height} screenshot, foreground app: {_foreground() or 'unknown'}"
    return [note, Image(data=buf.getvalue(), format="jpeg")]


@mcp.tool()
def read_screen(max_elements: int = 150) -> str:
    """List the visible UI elements as text: label, kind and tap point (screenshot pixels).

    Cheaper and more exact than a screenshot for finding buttons and fields.
    """
    # The dump fails while the screen is animating, so retry; the old file is
    # removed first so a stale screen is never returned.
    dump = (
        "rm -f /sdcard/window_dump.xml; "
        "uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1; "
        "cat /sdcard/window_dump.xml 2>/dev/null || true"
    )
    start = -1
    for _ in range(3):
        xml = adb("exec-out", dump, timeout=60)
        start = xml.find("<?xml")
        if start >= 0:
            break
        time.sleep(1)
    if start < 0:
        raise RuntimeError("The screen could not be read (it may be animating or secured). Try screenshot.")
    root = ET.fromstring(xml[start:])
    s = _scale()
    lines = []
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        clickable = node.get("clickable") == "true"
        editable = (node.get("class") or "").endswith("EditText")
        if not (text or desc or clickable or editable):
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        cx, cy = round((x1 + x2) / 2 / s), round((y1 + y2) / 2 / s)
        kind = (node.get("class") or "").rsplit(".", 1)[-1]
        flags = [f for f, on in (
            ("tap", clickable), ("edit", editable),
            ("scroll", node.get("scrollable") == "true"),
            ("checked", node.get("checked") == "true"),
            ("focused", node.get("focused") == "true"),
        ) if on]
        label = text or desc or "(no label)"
        if text and desc and desc != text:
            label = f"{text} / {desc}"
        lines.append(f"({cx},{cy}) {kind}: {label[:120]}" + (f" [{' '.join(flags)}]" if flags else ""))
        if len(lines) >= max_elements:
            lines.append("... more elements not shown")
            break
    header = f"foreground app: {_foreground() or 'unknown'}"
    return header + "\n" + ("\n".join(lines) if lines else "(no readable elements)")


@mcp.tool()
def tap(x: int, y: int) -> str:
    """Tap at a point given in screenshot pixels."""
    _guard()
    dx, dy = _to_device(x, y)
    sh(f"input tap {dx} {dy}")
    _log("tap", x=x, y=y)
    return f"Tapped ({x},{y})."


@mcp.tool()
def long_press(x: int, y: int, duration_ms: int = 800) -> str:
    """Press and hold at a point given in screenshot pixels."""
    _guard()
    dx, dy = _to_device(x, y)
    duration_ms = max(300, min(int(duration_ms), 5000))
    sh(f"input swipe {dx} {dy} {dx} {dy} {duration_ms}")
    _log("long_press", x=x, y=y, duration_ms=duration_ms)
    return f"Long-pressed ({x},{y}) for {duration_ms} ms."


@mcp.tool()
def swipe(x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> str:
    """Swipe or drag between two points in screenshot pixels.

    To scroll a page down, swipe upward (start low, end high).
    """
    _guard()
    ax, ay = _to_device(x1, y1)
    bx, by = _to_device(x2, y2)
    duration_ms = max(50, min(int(duration_ms), 5000))
    sh(f"input swipe {ax} {ay} {bx} {by} {duration_ms}")
    _log("swipe", x1=x1, y1=y1, x2=x2, y2=y2, duration_ms=duration_ms)
    return f"Swiped ({x1},{y1}) -> ({x2},{y2})."


@mcp.tool()
def type_text(text: str, press_enter: bool = False) -> str:
    """Type text into the focused field. ASCII only.

    Tap the field first and make sure it has focus (keyboard showing) before
    typing; characters sent while a screen is still opening are lost.
    """
    _guard()
    if not text.isascii():
        raise RuntimeError("Only plain ASCII text can be typed over adb. Remove emoji and non-Latin characters.")
    lines = text.split("\n")
    for i, line in enumerate(lines):
        # `input text` turns "%s" into a space, so a literal "%s" is sent in two parts.
        for chunk in re.split(r"(?<=%)(?=s)", line):
            if chunk:
                sh("input text " + _quote(chunk.replace(" ", "%s")))
        if i < len(lines) - 1:
            sh("input keyevent 66")
    if press_enter:
        sh("input keyevent 66")
    _log("type_text", text=text, press_enter=press_enter)
    return f"Typed {len(text)} characters."


KEYS = {
    "home": 3, "back": 4, "recents": 187, "enter": 66, "backspace": 67,
    "tab": 61, "escape": 111, "menu": 82, "search": 84, "power": 26, "wake": 224,
    "volume_up": 24, "volume_down": 25, "media_play_pause": 85,
    "up": 19, "down": 20, "left": 21, "right": 22, "paste": 279,
}
_SHADE = {"notifications": "cmd statusbar expand-notifications", "quick_settings": "cmd statusbar expand-settings"}
_ALWAYS_ALLOWED = {"home", "back", "recents", "wake", "volume_up", "volume_down"}


@mcp.tool()
def press_key(key: str, repeat: int = 1) -> str:
    """Press a phone key.

    Keys: home, back, recents, enter, backspace, tab, escape, menu, search, power,
    wake, volume_up, volume_down, media_play_pause, up, down, left, right, paste,
    notifications, quick_settings.
    """
    key = key.strip().lower()
    if key not in KEYS and key not in _SHADE:
        raise RuntimeError(f"Unknown key '{key}'. Valid: {', '.join([*KEYS, *_SHADE])}")
    if key not in _ALWAYS_ALLOWED:
        _guard()
    repeat = max(1, min(int(repeat), 30))
    for _ in range(repeat):
        sh(_SHADE[key] if key in _SHADE else f"input keyevent {KEYS[key]}")
    _log("press_key", key=key, repeat=repeat)
    return f"Pressed {key}" + (f" x{repeat}." if repeat > 1 else ".")


def _packages(third_party_only: bool) -> list[str]:
    out = sh("pm list packages" + (" -3" if third_party_only else ""))
    return sorted(ln[8:].strip() for ln in out.splitlines() if ln.startswith("package:"))


@mcp.tool()
def list_apps(filter: str = "", include_system: bool = False) -> str:
    """List installed app package names, optionally filtered by a keyword."""
    pkgs = _packages(third_party_only=not include_system and not filter)
    if filter:
        pkgs = [p for p in pkgs if filter.lower() in p.lower()]
    return "\n".join(pkgs) if pkgs else "No matching apps."


@mcp.tool()
def open_app(app: str) -> str:
    """Open an app by package name (com.whatsapp) or a keyword in it (whatsapp)."""
    pkgs = _packages(third_party_only=False)
    needle = app.strip().lower().replace(" ", "")
    exact = [p for p in pkgs if p.lower() == needle]
    matches = exact or [p for p in pkgs if needle in p.lower()]
    if not matches:
        raise RuntimeError(f"No installed app matches '{app}'. Try list_apps, or tap its icon on the home screen.")
    if len(matches) > 1:
        return "Several apps match, pick one package name:\n" + "\n".join(matches[:25])
    pkg = matches[0]
    if _is_blocked(pkg):
        raise RuntimeError(f"Refused: {pkg} is on the protected list (banking and payments).")
    out = sh(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
    if "No activities found" in out:
        raise RuntimeError(f"{pkg} has no launcher screen to open.")
    _log("open_app", package=pkg)
    return f"Opened {pkg}."


@mcp.tool()
def open_url(url: str) -> str:
    """Open a web link (http or https) in the phone's default browser or app."""
    if not re.match(r"^https?://[^\s]+$", url):
        raise RuntimeError("Only http and https links can be opened.")
    sh("am start -a android.intent.action.VIEW -d " + _quote(url))
    _log("open_url", url=url)
    return f"Opened {url}."


@mcp.tool()
def wait(seconds: float = 1.0) -> str:
    """Pause while the phone loads or animates (at most 10 seconds)."""
    seconds = max(0.0, min(float(seconds), 10.0))
    time.sleep(seconds)
    return f"Waited {seconds:g} s."


# ------------------------------------------------------------------------ main

def main() -> None:
    ap = argparse.ArgumentParser(description="Android phone control MCP server")
    ap.add_argument("--http", action="store_true", help="serve over HTTP instead of stdio")
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()

    if not args.http:
        mcp.run(transport="stdio")
        return

    if not CONFIG.get("remote_key"):
        CONFIG["remote_key"] = secrets.token_urlsafe(32)
        CONFIG_PATH.write_text(json.dumps(CONFIG, indent=2), encoding="utf-8")
    path = f"/{CONFIG['remote_key']}/mcp"
    mcp.settings.host = "127.0.0.1"
    mcp.settings.port = args.port
    mcp.settings.streamable_http_path = path
    # Requests arrive through a tunnel with its own Host header; the secret
    # path is what keeps strangers out.
    mcp.settings.transport_security = TransportSecuritySettings(enable_dns_rebinding_protection=False)
    print(f"Local address:  http://127.0.0.1:{args.port}{path}", file=sys.stderr)
    print(f"Through a tunnel, give the app:  https://<your-tunnel-host>{path}", file=sys.stderr)
    mcp.run(transport="streamable-http")


if __name__ == "__main__":
    main()
