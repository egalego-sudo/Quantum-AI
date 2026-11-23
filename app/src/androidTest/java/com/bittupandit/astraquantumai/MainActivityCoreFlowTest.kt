package com.bittupandit.astraquantumai

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityCoreFlowTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun disableDeviceLockForAutomatedUiTest() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences("quantum_app_security", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("app_lock_enabled", false)
                .commit()

            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val secure = EncryptedSharedPreferences.create(
                context,
                "quantum_private_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val seededHistory = JSONArray()
                .put(JSONObject().put("from", "USER").put("text", "QUANTUM-AI chat restore smoke check"))
                .put(JSONObject().put("from", "QUANTUM AI").put("text", "नमस्ते — chat history restored"))
            secure.edit().clear().putString("chat_history", seededHistory.toString()).commit()
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chatNavigationAndPrivateMemorySurviveActivityRecreation() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Memory", substring = false).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("AI Chat", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("AI CONVERSATION").fetchSemanticsNode()
        composeRule.onNodeWithTag("chat_message_QUANTUM-AI chat restore smoke check").fetchSemanticsNode()

        repeat(4) { composeRule.onNodeWithTag("main_navigation").performTouchInput { swipeLeft() } }
        composeRule.onNodeWithText("Memory", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("ADVANCED PRIVATE MEMORY").fetchSemanticsNode()

        // Personal context must be opt-in, not silently enabled by default.
        composeRule.onAllNodes(isToggleable()).onFirst().assertIsOff()

        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("QUANTUM-AI emulator persistence check")
        composeRule.onNodeWithText("Memory में सेव करें").performClick()
        composeRule.onNodeWithText("QUANTUM-AI emulator persistence check", substring = true).fetchSemanticsNode()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Memory", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        repeat(4) { composeRule.onNodeWithTag("main_navigation").performTouchInput { swipeRight() } }
        composeRule.onNodeWithText("AI Chat", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("chat_message_QUANTUM-AI chat restore smoke check").fetchSemanticsNode()
        repeat(4) { composeRule.onNodeWithTag("main_navigation").performTouchInput { swipeLeft() } }
        composeRule.onNodeWithText("Memory", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("ADVANCED PRIVATE MEMORY").fetchSemanticsNode()
        composeRule.onNodeWithText("QUANTUM-AI emulator persistence check", substring = true).fetchSemanticsNode()

        // Project notes are private local data and should survive Activity recreation too.
        composeRule.onNodeWithText("Projects", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("PROJECT WORKSPACE").fetchSemanticsNode()
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("QUANTUM-AI project persistence check")
        composeRule.onNodeWithText("प्रोजेक्ट / नोट सेव करें").performClick()
        composeRule.onNodeWithText("QUANTUM-AI project persistence check", substring = true).fetchSemanticsNode()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Settings", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        repeat(4) { composeRule.onNodeWithTag("main_navigation").performTouchInput { swipeLeft() } }
        composeRule.onNodeWithText("Projects", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("QUANTUM-AI project persistence check", substring = true).fetchSemanticsNode()

        composeRule.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("CONNECTION & SECURITY SETTINGS").fetchSemanticsNode()
        composeRule.onNodeWithText("App Lock").fetchSemanticsNode()

        // Leave the emulator's app data clean for any following tests.
        composeRule.onNodeWithText("Projects", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("सभी प्रोजेक्ट नोट मिटाएँ").performClick()
        composeRule.onNodeWithText("Memory", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("सभी Memory मिटाएँ और उपयोग बंद करें").performClick()
    }
}
