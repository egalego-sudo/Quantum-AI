package com.bittupandit.astraquantumai

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreFlowAndMemoryInstrumentedTest {
    @Test
    fun appActivityLaunchesAndRemainsAlive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val securityPrefs = context.getSharedPreferences("quantum_app_security", Context.MODE_PRIVATE)
        val previousLockSetting = securityPrefs.getBoolean("app_lock_enabled", true)
        assertTrue(
            "Could not disable app lock for the emulator test",
            securityPrefs.edit().putBoolean("app_lock_enabled", false).commit()
        )

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertFalse("MainActivity unexpectedly finished after launch", activity.isFinishing)
            }
        } finally {
            scenario.close()
            securityPrefs.edit().putBoolean("app_lock_enabled", previousLockSetting).commit()
        }
    }

    @Test
    fun encryptedSavedMemorySurvivesStoreReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = securePreferences(context)
        val previousMemory = prefs.getString("memories", null)
        val marker = "QUANTUM_CI_MEMORY_PERSISTENCE_CHECK"

        try {
            assertTrue("Could not write encrypted memory", prefs.edit().putString("memories", marker).commit())
            val reopenedPrefs = securePreferences(context)
            assertTrue(
                "Saved memory did not survive reopening encrypted preferences",
                reopenedPrefs.getString("memories", null) == marker
            )
        } finally {
            val cleanup = prefs.edit()
            if (previousMemory == null) cleanup.remove("memories") else cleanup.putString("memories", previousMemory)
            cleanup.commit()
        }
    }
}
