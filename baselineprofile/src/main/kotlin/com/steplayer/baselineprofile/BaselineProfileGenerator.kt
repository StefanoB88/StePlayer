package com.steplayer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, maxIterations = 5, stableIterations = 2, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        ensureLibrary()
        browse()
    }
}
