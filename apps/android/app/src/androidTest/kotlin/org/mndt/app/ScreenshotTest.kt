package org.mndt.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView
import org.mndt.app.ui.post.Category
import org.mndt.mesh.MeshPermissions
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.cleanstatusbar.CleanStatusBar
import tools.fastlane.screengrab.cleanstatusbar.IconVisibility
import tools.fastlane.screengrab.locale.LocaleTestRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Play Store screenshots for `fastlane screengrab`; build with -PmosimoPlcRegistration=false.
class ScreenshotTest {
    // Saves under the locale fastlane passes (ja-JP), not the device default.
    @get:Rule(order = 0) val locale = LocaleTestRule()
    // Pre-granted so first launch shows the app, not the system permission dialogs.
    @get:Rule(order = 1) val permissions: GrantPermissionRule = GrantPermissionRule.grant(*MeshPermissions.required(),
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    companion object {
        // A fix (the emulator's is in California) flies the map to zoom 14 off Japan's tiles and pins, so the providers are
        // swapped for test ones, which start disabled and never fix. Location off instead gets a Play services warning on top.
        private val providers = listOf("fused", "gps", "network")
        @JvmStatic @BeforeClass fun setUp() {
            CleanStatusBar().setMobileNetworkVisibility(IconVisibility.HIDE).enable()
            shell("appops set --uid 2000 android:mock_location allow")
            providers.forEach { shell("cmd location providers add-test-provider $it") }
        }
        @JvmStatic @AfterClass fun tearDown() {
            CleanStatusBar.disable()
            providers.forEach { shell("cmd location providers remove-test-provider $it") }
            shell("appops set --uid 2000 android:mock_location default")
        }
        // Reading the output waits for the command to finish.
        private fun shell(cmd: String) =
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    }

    @Test fun capture() {
        Screengrab.setDefaultScreenshotStrategy(UiAutomatorScreenshotStrategy())
        waitForText("今、ここの状況は？")
        val pin = (Category.entries.map { it.label } + "投稿").map { hasContentDescription(it) }.reduce { a, b -> a or b }
        compose.waitUntil("a post pin on the map", 60_000) { compose.onAllNodes(pin).fetchSemanticsNodes().isNotEmpty() }
        awaitMapIdle()
        shot("01_map")
        compose.onNodeWithText("タイムライン").performClick()
        waitForText("公式情報のみ")
        waitForText("マップで見る")
        // The tab slide runs on the test clock, and the public-info fetch shows a pull-to-refresh spinner until every
        // older page is in (15s timeout each); let both finish.
        compose.waitUntil("the timeline spinner gone", 120_000) { compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        shot("02_timeline")
    }

    private fun waitForText(text: String) = compose.waitUntil("「$text」 shown", 30_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** Tiles load off the Compose clock: wait for MapLibre's own idle, i.e. every visible tile drawn. */
    private fun awaitMapIdle() {
        val idle = CountDownLatch(1)
        fun find(v: View): MapView? = v as? MapView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        compose.runOnUiThread {
            find(compose.activity.window.decorView)!!.apply { addOnDidBecomeIdleListener { idle.countDown() } }.getMapAsync { it.triggerRepaint() }
        }
        check(idle.await(60, TimeUnit.SECONDS)) { "map tiles never finished loading" }
    }

    private fun shot(name: String) {
        // API 34+ SystemUI reads "fully" only from the wifi command; screengrab sends it separately, leaving a "!" on wifi.
        // Sent per shot because SystemUI drops it right after boot.
        InstrumentationRegistry.getInstrumentation().targetContext.sendBroadcast(Intent("com.android.systemui.demo").setPackage("com.android.systemui")
            .putExtra("command", "network").putExtra("wifi", "show").putExtra("level", "4").putExtra("fully", "true"))
        Thread.sleep(500)
        Screengrab.screenshot(name)
    }
}
