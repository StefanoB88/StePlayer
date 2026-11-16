#!/usr/bin/env python3
"""Screenshots of every Android screen on an EMULATOR in harsh conditions (small phone, 3-button navigation,
large font) to spot clipped or unreadable layouts. Needs the dev catalogue on port 8082 (see shots-android.py).
Usage: screens-audit.py <serial> <apk> <out-dir>
"""
import os, re, subprocess, sys, time

SERIAL, APK, OUT = sys.argv[1:4]
PKG = "com.steplayer.app"
LIST = "http://10.0.2.2:8082/dev/list.m3u"
os.makedirs(OUT, exist_ok=True)


def adb(*a, check=True):
    return subprocess.run(["adb", "-s", SERIAL, *a], check=check, capture_output=True, text=True).stdout


def dump():
    for _ in range(3):
        r = subprocess.run(["adb", "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/u.xml"], capture_output=True, text=True)
        if r.returncode == 0:
            return adb("shell", "cat", "/sdcard/u.xml")
        time.sleep(2)
    return ""


def find(text, xml, attr="text"):
    m = re.search(r'%s="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % (attr, re.escape(text)), xml)
    return None if not m else ((int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2)


def tap(text, wait=1.5, attr="text", scroll=True):
    for _ in range(6):
        xml = dump()
        if "isn't responding" in xml:
            p = find("Wait", xml)
            if p:
                adb("shell", "input", "tap", *map(str, p))
            continue
        p = find(text, xml, attr)
        if p:
            adb("shell", "input", "tap", *map(str, p))
            time.sleep(wait)
            return True
        if not scroll:
            break
        adb("shell", "input", "swipe", "360", "1100", "360", "500", "300")
        time.sleep(1)
    print(f"  ! not found: {text}")
    return False


n = [0]


def shot(name, wait=1.5):
    time.sleep(wait)
    n[0] += 1
    path = os.path.join(OUT, f"{n[0]:02d}-{name}.png")
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True).stdout)
    print(path)


def scroll_end(name):
    for _ in range(4):
        adb("shell", "input", "swipe", "360", "1200", "360", "300", "250")
        time.sleep(0.6)
    shot(name + "-end")


def back(wait=1.5):
    adb("shell", "input", "keyevent", "BACK")
    time.sleep(wait)


if "sdk" not in adb("shell", "getprop", "ro.product.model").lower():
    raise SystemExit("refusing: not an emulator")

# Harsh conditions: 360 dp wide phone, 3-button navigation, font +15 %.
adb("shell", "wm", "size", "720x1520")
adb("shell", "wm", "density", "320")
adb("shell", "settings", "put", "system", "font_scale", "1.15")
adb("shell", "cmd", "overlay", "enable", "com.android.internal.systemui.navbar.threebutton", check=False)
time.sleep(3)
adb("uninstall", PKG, check=False)
adb("install", "-r", APK)
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(6)
try:
    shot("welcome")
    scroll_end("welcome")
    tap("Confermo che userò StePlayer solo con contenuti che ho il diritto di guardare", 0.8)
    tap("Ho capito e accetto", 2)
    shot("add-server")
    tap("Lista M3U")
    shot("add-m3u")
    scroll_end("add-m3u")
    fields = re.findall(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump())
    adb("shell", "input", "swipe", "360", "500", "360", "1200", "250")
    time.sleep(1)
    fields = re.findall(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump())
    x1, y1, x2, y2 = map(int, fields[1])
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)
    for chunk in re.findall(r".{1,5}", LIST):
        adb("shell", "input", "text", chunk)
        time.sleep(0.3)
    back(1)
    tap("Salva e connetti", 2)
    shot("sync", 3)
    for _ in range(80):
        if 'text="Film aggiunti di recente"' in dump():
            break
        time.sleep(3)
    shot("home", 3)
    scroll_end("home")
    tap("Live", 3); shot("live"); scroll_end("live")
    tap("Guida TV", 3, attr="content-desc", scroll=False) or tap("Guida", 3, attr="content-desc", scroll=False)
    shot("guide", 3); back()
    tap("Film", 3); shot("movies")
    xml = dump()
    posters = re.findall(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    adb("shell", "input", "tap", "120", "520")
    shot("movie-detail", 3); scroll_end("movie-detail"); back()
    tap("Serie", 3); shot("series")
    adb("shell", "input", "tap", "120", "520")
    shot("series-detail", 3); scroll_end("series-detail"); back()
    tap("Download", 2); shot("downloads")
    tap("Home", 2)
    tap("Cerca", 2, attr="content-desc", scroll=False); shot("search")
    adb("shell", "input", "text", "Valmora"); time.sleep(2); back(1); shot("search-results"); back()
    tap("Impostazioni", 2, attr="content-desc", scroll=False); shot("settings"); scroll_end("settings")
    for page in ["Riproduzione", "Sottotitoli", "Invia una lista alla TV", "Test della connessione"]:
        adb("shell", "input", "swipe", "360", "400", "360", "1300", "200"); time.sleep(0.5)
        adb("shell", "input", "swipe", "360", "400", "360", "1300", "200"); time.sleep(0.5)
        if tap(page, 2.5):
            shot("settings-" + page.split()[0].lower()); scroll_end("settings-" + page.split()[0].lower()); back()
finally:
    adb("shell", "wm", "size", "reset")
    adb("shell", "wm", "density", "reset")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
