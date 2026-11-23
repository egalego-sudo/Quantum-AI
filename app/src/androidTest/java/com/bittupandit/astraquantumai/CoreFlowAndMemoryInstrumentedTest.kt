package com.bittupandit.astraquantumai

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreFlowAndMemoryInstrumentedTest {
    @Test
    fun appLaunchesAndOpensMemoryPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val securityPrefs = context.getSharedPreferences("quantum_app_security", Context.MODE_PRIVATE)
        val previousLockSetting = securityPrefs.getBoolean("app_lock_enabled", true)
        securityPrefs.edit().putBoolean("app_lock_enabled", false).commit()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue("Home navigation did not appear", device.wait(Until.hasObject(By.text("Home")), 20_000))
            val memoryTab = device.findObject(By.text("Memory"))
            assertTrue("Memory navigation tab is missing", memoryTab != null)
            memoryTab.click()
            assertTrue(
                "Memory page did not open",
                device.wait(Until.hasObject(By.text("ADVANCED PRIVATE MEMORY")), 10_000)
            )
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
