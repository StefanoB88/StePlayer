#!/usr/bin/env python3
"""Google Play screenshots from an Android EMULATOR (never a physical phone).

Needs the fictional dev catalogue reachable from the emulator:
    cd tizenApp && PNG_ART=1 NO_EXPIRY=1 PUBLIC=http://10.0.2.2:8082 node dev/server.mjs 8082
Usage:
    shots-android.py <serial> <apk> <out-dir> <prefix> phone|tv [WxH density]
phone: touch UI (phone or tablet, the size override picks the form factor); tv: D-pad UI.
"""
import os, re, subprocess, sys, time

SERIAL, APK, OUT, PREFIX, MODE = sys.argv[1:6]
SIZE = sys.argv[6] if len(sys.argv) > 6 else None
DENSITY = sys.argv[7] if len(sys.argv) > 7 else None
PKG = "com.steplayer.app"
LIST = "http://10.0.2.2:8082/dev/list.m3u"


def adb(*args, check=True):
    return subprocess.run(["adb", "-s", SERIAL, *args], check=check, capture_output=True, text=True).stdout


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    return adb("shell", "cat", "/sdcard/u.xml")


def bounds(text, xml=None):
    xml = xml or dump()
    m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text), xml)
    return None if not m else ((int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2)


def dismiss_anr(xml):
    """The slow emulator sometimes shows "… isn't responding": keep waiting."""
    if "isn't responding" in xml:
        p = bounds("Wait", xml)
        if p:
            adb("shell", "input", "tap", str(p[0]), str(p[1]))
            time.sleep(2)
        return True
    return False


def tap_text(text, wait=1.2):
    p = None
    for attempt in range(6):
        xml = dump()
        if dismiss_anr(xml):
            continue
        p = bounds(text, xml)
        # Fully on screen? Otherwise scroll a little (forms and the legal notice are taller than the screen).
        if p and p[1] < int(adb("shell", "wm", "size").split()[-1].split("x")[1]) * 0.9:
            break
        adb("shell", "input", "swipe", "540", "1400", "540", "700", "300")
        time.sleep(1)
    if not p:
        raise SystemExit(f"not on screen: {text}")
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    time.sleep(wait)


def key(name, n=1, gap=0.5):
    for _ in range(n):
        adb("shell", "input", "keyevent", name)
        time.sleep(gap)


def wait_for(text, seconds=240):
    end = time.time() + seconds
    while time.time() < end:
        xml = dump()
        dismiss_anr(xml)
        if f'text="{text}"' in xml:
            return
        time.sleep(3)
    raise SystemExit(f"timeout waiting for: {text}")


def shot(name, wait=2.5):
    time.sleep(wait)
    path = os.path.join(OUT, f"{PREFIX}-{name}.png")
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], check=True, capture_output=True).stdout)
    print(path)


def type_slowly(text):
    # `input text` drops characters on long strings: send short chunks.
    for chunk in re.findall(r".{1,5}", text):
        adb("shell", "input", "text", chunk.replace("&", "\\&"))
        time.sleep(0.35)


model = adb("shell", "getprop", "ro.product.model")
if "sdk" not in model.lower() and "emulator" not in SERIAL:
    raise SystemExit(f"refusing: {SERIAL} is not an emulator ({model.strip()})")

os.makedirs(OUT, exist_ok=True)
if SIZE:
    adb("shell", "wm", "size", SIZE)
if DENSITY:
    adb("shell", "wm", "density", DENSITY)
# Clean status bar (System UI demo mode).
adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
for cmd in (["enter"], ["clock", "-e", "hhmm", "0941"], ["battery", "-e", "level", "100", "-e", "plugged", "false"],
            ["network", "-e", "wifi", "show", "-e", "level", "4", "-e", "mobile", "hide"], ["notifications", "-e", "visible", "false"]):
    adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", *cmd, check=False)

adb("uninstall", PKG, check=False)
adb("install", "-r", APK)
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LEANBACK_LAUNCHER" if MODE == "tv" else "android.intent.category.LAUNCHER", "1")
time.sleep(6)

# First launch: legal notice, then the M3U list.
if MODE == "tv":
    key("DPAD_DOWN"); key("DPAD_CENTER"); key("DPAD_DOWN"); key("DPAD_CENTER")
    time.sleep(2)
    tap_text("Inserisci con il telecomando")
    tap_text("Lista M3U")
else:
    tap_text("Confermo che userò StePlayer solo con contenuti che ho il diritto di guardare", 0.8)
    tap_text("Ho capito e accetto", 2)
    tap_text("Lista M3U")
xml = dump()
fields = re.findall(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
x1, y1, x2, y2 = map(int, fields[1])
adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
time.sleep(1)
type_slowly(LIST)
key("BACK")
tap_text("Salva e connetti")
wait_for("Film aggiunti di recente")
time.sleep(4)

if MODE == "tv":
    shot("1-home", 3)
    tap_text("Live", 3); key("DPAD_RIGHT", 2, 0.6); key("DPAD_DOWN", 2, 0.5)
    shot("2-live", 2)
    tap_text("Film", 3)
    shot("3-movies", 3)
    tap_text("Serie", 3)
    shot("4-series", 3)
else:
    shot("1-home", 3)
    tap_text("Live", 3)
    shot("2-live", 2)
    tap_text("Film", 3)
    shot("3-movies", 3)
    tap_text("Serie", 3)
    shot("4-series", 3)

adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit", check=False)
if SIZE:
    adb("shell", "wm", "size", "reset")
if DENSITY:
    adb("shell", "wm", "density", "reset")
