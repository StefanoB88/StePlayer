#!/usr/bin/env python3
"""Play Console video for the FOREGROUND_SERVICE_DATA_SYNC declaration: a user-started download with its
progress notification. Records an Android EMULATOR (never a physical phone) with the public demo list.
Usage: video-download.py <serial> <apk> <out.mp4>
"""
import re, subprocess, sys, time

SERIAL, APK, OUT = sys.argv[1:4]
PKG = "com.steplayer.app"
LIST = "https://stefanob88.github.io/steplayerwebsite/demo.m3u"


def adb(*args, check=True):
    return subprocess.run(["adb", "-s", SERIAL, *args], check=check, capture_output=True, text=True).stdout


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    return adb("shell", "cat", "/sdcard/u.xml")


def find(text, xml, attr="text", partial=False):
    pat = r'%s="%s%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % (attr, re.escape(text), r'[^"]*' if partial else "")
    m = re.search(pat, xml)
    return None if not m else ((int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2)


def tap(text, wait=1.5, attr="text", partial=False):
    for _ in range(6):
        xml = dump()
        if "isn't responding" in xml:
            p = find("Wait", xml)
            if p:
                adb("shell", "input", "tap", *map(str, p))
            time.sleep(2)
            continue
        p = find(text, xml, attr, partial)
        if p:
            adb("shell", "input", "tap", *map(str, p))
            time.sleep(wait)
            return
        adb("shell", "input", "swipe", "540", "1500", "540", "800", "300")
        time.sleep(1)
    raise SystemExit(f"not on screen: {text}")


def wait_for(text, seconds=120):
    end = time.time() + seconds
    while time.time() < end:
        if f'text="{text}' in dump():
            return
        time.sleep(2)
    raise SystemExit(f"timeout: {text}")


model = adb("shell", "getprop", "ro.product.model")
if "sdk" not in model.lower():
    raise SystemExit(f"refusing: {SERIAL} is not an emulator ({model.strip()})")

adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
for cmd in (["enter"], ["clock", "-e", "hhmm", "0941"], ["battery", "-e", "level", "100", "-e", "plugged", "false"],
            ["network", "-e", "wifi", "show", "-e", "level", "4", "-e", "mobile", "hide"], ["notifications", "-e", "visible", "true"]):
    adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", *cmd, check=False)
adb("uninstall", PKG, check=False)
adb("install", "-r", APK)
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(6)

# Setup (not recorded): legal notice and the public demo list.
tap("Confermo che userò StePlayer solo con contenuti che ho il diritto di guardare", 0.8)
tap("Ho capito e accetto", 2)
tap("Lista M3U")
fields = re.findall(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump())
x1, y1, x2, y2 = map(int, fields[1])
adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
time.sleep(1)
for chunk in re.findall(r".{1,5}", LIST):
    adb("shell", "input", "text", chunk)
    time.sleep(0.35)
adb("shell", "input", "keyevent", "BACK")
time.sleep(0.8)
tap("Salva e connetti")
wait_for("Film", 120)
time.sleep(4)

# Clear unrelated system notifications (screen lock tip, keyboard layout) so the shade shows only ours.
adb("shell", "cmd", "statusbar", "expand-notifications")
time.sleep(2)
xml = dump()
p = find("Clear all", xml)
if p:
    adb("shell", "input", "tap", *map(str, p))
    time.sleep(1.5)
adb("shell", "cmd", "statusbar", "collapse")
time.sleep(1.5)

# Recorded part.
rec = subprocess.Popen(["adb", "-s", SERIAL, "shell", "screenrecord", "--time-limit", "90", "--bit-rate", "8000000", "/sdcard/demo.mp4"])
time.sleep(2)
tap("Film", 2.5)
tap("ED", 3)                               # the Elephants Dream poster (the card, not its caption)
tap("Scarica", 3)                          # the user starts the download; the button shows the percentage
adb("shell", "cmd", "statusbar", "expand-notifications")
time.sleep(8)                              # progress notification of the foreground task
adb("shell", "cmd", "statusbar", "collapse")
time.sleep(2)
adb("shell", "input", "keyevent", "BACK")
time.sleep(2)
tap("Download", 3)                         # the download in the app
# screenrecord adds no frames while the screen is still: gentle overscrolls keep the last screen in the video.
for _ in range(4):
    adb("shell", "input", "swipe", "540", "900", "540", "1150", "400")
    time.sleep(1.5)
adb("shell", "pkill", "-INT", "screenrecord", check=False)
rec.wait(timeout=30)
time.sleep(2)
adb("pull", "/sdcard/demo.mp4", OUT)
adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit", check=False)
print(OUT)
