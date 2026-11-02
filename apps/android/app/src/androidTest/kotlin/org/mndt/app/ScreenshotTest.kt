package org.mndt.app

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.cleanstatusbar.CleanStatusBar
import tools.fastlane.screengrab.locale.LocaleTestRule

// Play Store screenshots for `fastlane screengrab`; build with -PmosimoPlcRegistration=false.
class ScreenshotTest {
    // Saves under the locale fastlane passes (ja-JP), not the device default.
    @get:Rule(order = 0) val locale = LocaleTestRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic @BeforeClass fun cleanStatusBar() = CleanStatusBar.enableWithDefaults()
        @JvmStatic @AfterClass fun restoreStatusBar() = CleanStatusBar.disable()
    }

    @Test fun capture() {
        Screengrab.setDefaultScreenshotStrategy(UiAutomatorScreenshotStrategy())
        compose.waitUntil(30_000) { compose.onAllNodesWithText("発信者準備済み", substring = true).fetchSemanticsNodes().isNotEmpty() }
        Screengrab.screenshot("01_top")
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        Screengrab.screenshot("02_posts")
    }
}
