package com.steplayer.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "com.steplayer.app"

private fun listUrl(): String =
    InstrumentationRegistry.getArguments().getString("listUrl") ?: "http://10.0.2.2:8082/dev/list.m3u"

/** First launch: legal notice + add the (fictional, local) test list with the remote, then wait for the library. */
fun MacrobenchmarkScope.ensureLibrary() {
    val d = device
    android.util.Log.i("SteJourney", "ensureLibrary start")
    if (d.wait(Until.hasObject(By.text("Benvenuto in StePlayer")), 5_000)) {
        // The checkbox and the button sit below the fold on a 1080p TV: reach them with the D-pad, like a user.
        d.pressDPadDown(); Thread.sleep(400); d.pressDPadCenter(); Thread.sleep(400)
        d.pressDPadDown(); Thread.sleep(400); d.pressDPadCenter()
    }
    if (d.wait(Until.hasObject(By.text("Inserisci con il telecomando")), 4_000)) {
        d.findObject(By.text("Inserisci con il telecomando"))?.click()
        d.wait(Until.hasObject(By.text("Lista M3U")), 3_000)
        d.findObject(By.text("Lista M3U"))?.click()
        d.waitForIdle()
        // Set the text through accessibility (no keyboard, so no Back needed to close it: Back on the first
        // screen would send the app to the background). Compose may redraw the form: re-find until it sticks.
        for (attempt in 1..8) {
            runCatching { d.findObjects(By.clazz("android.widget.EditText"))[1].text = listUrl() }
            Thread.sleep(500)
            if (d.hasObject(By.text(listUrl()))) break
        }
        screenshot("before-save")
        d.findObject(By.text("Salva e connetti"))?.click()
        d.wait(Until.hasObject(By.text("Film aggiunti di recente")), 240_000)
    }
    if (!d.wait(Until.hasObject(By.pkg(PACKAGE).text("Live")), 30_000)) {
        screenshot("library-not-loaded")
        error("library not loaded: the journey would measure nothing")
    }
}

/** Debug aid when the journey can't reach the library: screen + visible texts in /data/local/tmp (survives the run). */
private fun MacrobenchmarkScope.screenshot(name: String) {
    device.executeShellCommand("screencap -p /data/local/tmp/sp-$name.png")
    val out = java.io.ByteArrayOutputStream()
    runCatching { device.dumpWindowHierarchy(out) }
    android.util.Log.e("SteJourney", "$name: " + Regex("text=\"([^\"]+)\"").findAll(out.toString()).map { it.groupValues[1] }.joinToString(" | "))
}

private fun MacrobenchmarkScope.dpad(times: Int, key: () -> Boolean) {
    repeat(times) { key(); Thread.sleep(120) }
}

/** The screens people use most, with the D-pad: Home rows, Live list, Film grid, a detail page, Serie. */
fun MacrobenchmarkScope.browse() {
    val d = device
    d.waitForIdle()
    // Home: the app starts with the focus on the rail; Right enters the content.
    dpad(1) { d.pressDPadRight() }
    dpad(3) { d.pressDPadDown() }
    dpad(10) { d.pressDPadRight() }
    dpad(10) { d.pressDPadLeft() }
    openTab("Live")
    d.wait(Until.hasObject(By.text("Tutti")), 10_000)
    Thread.sleep(1_000)
    dpad(2) { d.pressDPadRight() } // categories → channels
    dpad(25) { d.pressDPadDown() }
    dpad(10) { d.pressDPadUp() }
    openTab("Film")
    Thread.sleep(2_000)
    dpad(1) { d.pressDPadRight() }
    dpad(8) { d.pressDPadDown() }
    dpad(4) { d.pressDPadRight() }
    d.pressDPadCenter()
    d.wait(Until.hasObject(By.textContains("Riproduci")), 8_000)
    Thread.sleep(800)
    d.pressBack()
    Thread.sleep(800)
    openTab("Serie")
    Thread.sleep(2_000)
    dpad(1) { d.pressDPadRight() }
    dpad(6) { d.pressDPadDown() }
    screenshot("browse-end")
}

fun MacrobenchmarkScope.openTab(label: String) {
    val d = device
    d.pressBack() // content → rail (TV Back convention in MainShell)
    Thread.sleep(400)
    d.findObject(By.text(label))?.click()
    d.waitForIdle()
}
