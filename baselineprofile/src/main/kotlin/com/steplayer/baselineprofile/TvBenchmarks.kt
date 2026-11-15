package com.steplayer.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Startup and D-pad smoothness, without and with the Baseline Profile. */
@RunWith(AndroidJUnit4::class)
class TvBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun startupNoProfile() = startup(CompilationMode.None())
    @Test fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))
    @Test fun browseNoProfile() = browse(CompilationMode.None())
    @Test fun browseWithProfile() = browse(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE, metrics = listOf(StartupTimingMetric()), iterations = 5,
        startupMode = StartupMode.COLD, compilationMode = mode,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    private fun browse(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()), iterations = 3,
        startupMode = StartupMode.WARM, compilationMode = mode,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            ensureLibrary()
        },
    ) {
        browse()
    }
}
