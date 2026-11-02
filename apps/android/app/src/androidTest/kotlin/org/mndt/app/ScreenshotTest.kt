package org.mndt.app

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Rule
import org.junit.Test
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy

// Play Store screenshots for `mise run screenshots`; build with -PmosimoPlcRegistration=false.
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun capture() {
        Screengrab.setDefaultScreenshotStrategy(UiAutomatorScreenshotStrategy())
        compose.waitUntil(30_000) { compose.onAllNodesWithText("発信者準備済み", substring = true).fetchSemanticsNodes().isNotEmpty() }
        Screengrab.screenshot("01_top")
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        Screengrab.screenshot("02_posts")
    }
}
