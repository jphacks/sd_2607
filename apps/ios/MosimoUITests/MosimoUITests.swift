import XCTest

/// Drives the real app on a device or simulator: compose a located post and find it on the timeline.
final class MosimoUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        // Keeps test posts off public relays on a fresh install (DEBUG-only hook).
        app.launchArguments += ["-mosimo.test.relays", "wss://pds.oriom.dev"]
        app.launch()
        // System prompts (Bluetooth, location, local network) on first run.
        addUIInterruptionMonitor(withDescription: "permissions") { alert in
            for label in ["許可", "Allow", "Appを使用中は許可", "Allow While Using App", "OK"] where alert.buttons[label].exists {
                alert.buttons[label].tap(); return true
            }
            return false
        }
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
    }

    func testTabsRender() {
        let app = launch()
        XCTAssertTrue(app.tabBars.buttons["状況マップ"].waitForExistence(timeout: 10))
        shot(app, "map")
        app.tabBars.buttons["タイムライン"].tap(); shot(app, "timeline")
        app.tabBars.buttons["設定"].tap(); shot(app, "settings")
    }

    func testRelayAndClockLiveInAdvanced() {
        let app = launch()
        XCTAssertTrue(app.tabBars.buttons["設定"].waitForExistence(timeout: 10))
        app.tabBars.buttons["設定"].tap(); shot(app, "settings")
        XCTAssertFalse(app.switches["settings.relay"].exists)
        XCTAssertFalse(app.switches["端末時計を信頼"].exists)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "詳細設定")).firstMatch.tap()
        XCTAssertTrue(app.switches["settings.relay"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.switches["端末時計を信頼"].exists)
        shot(app, "advanced")
    }

    func testComposeAndPublishShowsOnTimeline() {
        let app = launch()
        XCTAssertTrue(app.tabBars.buttons["状況マップ"].waitForExistence(timeout: 10))
        app.tap() // lets the interruption monitor answer pending permission alerts
        let open = app.buttons["compose.open"]
        XCTAssertTrue(open.waitForExistence(timeout: 10))
        open.tap()
        // First post on this install: agree to the terms once.
        if app.buttons["terms.agree"].waitForExistence(timeout: 3) { app.buttons["terms.agree"].tap() }
        app.buttons["category.危険"].tap()
        let detail = app.textViews["compose.detail"].exists ? app.textViews["compose.detail"] : app.textFields["compose.detail"]
        detail.tap()
        let text = "UIテスト \(Int(Date().timeIntervalSince1970) % 100000)"
        detail.typeText(text)
        shot(app, "composer")
        let send = app.buttons["compose.send"]
        if !send.isEnabled {
            // No GPS fix (e.g. simulator): pick the map centre as the post location.
            app.buttons["地図で指定"].tap()
            app.buttons["この位置にする"].tap()
        }
        XCTAssertTrue(send.waitForExistence(timeout: 5))
        send.tap()
        app.tabBars.buttons["タイムライン"].tap()
        XCTAssertTrue(app.staticTexts[text].waitForExistence(timeout: 15))
        shot(app, "timeline-after-post")
    }
}
