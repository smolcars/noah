import Testing
@testable import Noah

/// Settings sits in Home's stack: deep links land on their page with Settings beneath, and
/// wide windows swap the trailing page without stacking pages under it.
struct SettingsNavigationTests {
    typealias Route = HomeView.Route

    @Test(arguments: [SettingsPage.recovery, .backup])
    func deepLinksLandOnTheirPageWithSettingsBeneath(page: SettingsPage) {
        #expect(SettingsPath.deepLink(page) == [.settings, .page(page)])
        // From a screen already open on Home (the map), Settings opens over it.
        #expect(SettingsPath.deepLink(page, from: [.map]) == [.map, .settings, .page(page)])
        // Opened again while Settings is open, it doesn't stack a second Settings.
        #expect(SettingsPath.deepLink(page, from: [.settings, .page(.vtxos), .page(.vtxo("a"))]) == [.settings, .page(page)])
    }

    /// Activity's VTXO, schedule and exit links switch to Home, which opens the page with Settings beneath.
    @MainActor @Test(arguments: [SettingsPage.vtxo("c:0"), .exit])
    func activityLinksLandOnHomeWithSettingsBeneath(page: SettingsPage) {
        let route = SettingsRoute()
        route.page = page
        #expect(route.take() == [.settings, .page(page)])
        #expect(route.page == nil)
        #expect(route.take() == nil) // Opened once: Home coming back doesn't open it again.
    }

    @Test func phonesPushRowsAndWideWindowsReplaceTheTrailingPage() {
        let open: [Route] = [.settings, .page(.vtxos), .page(.vtxo("a"))]
        #expect(SettingsPath.select(.backup, in: [.settings], wide: false) == [.settings, .page(.backup)])
        #expect(SettingsPath.select(.backup, in: open, wide: true) == [.settings, .page(.backup)])
        #expect(SettingsPath.select(.vtxo("a"), in: open, wide: true) == open)
        #expect(SettingsPath.base(open + [.page(.recurring)]) == [])
        #expect(SettingsPath.base([.map, .settings]) == [.map])
    }
}
