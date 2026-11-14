#if os(iOS)
import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Orientation lock used by the player (landscape on iPhone).
    static var orientationLock: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        KeyboardDismiss.install()
        // Scrolling a list/scroll view also lowers the keyboard (e.g. scrolling search results).
        UIScrollView.appearance().keyboardDismissMode = .onDrag
        return true
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : Self.orientationLock
    }

    /// Background URLSession wake-up for finished downloads.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        BackgroundDownloadEngine.backgroundCompletionHandler = completionHandler
    }

    private static var windowScene: UIWindowScene? { UIApplication.shared.connectedScenes.first as? UIWindowScene }

    /// iPhone: the interface is already landscape.
    @MainActor static var isLandscape: Bool { windowScene?.effectiveGeometry.interfaceOrientation.isLandscape ?? false }

    /// iPhone: turns the interface to landscape BEFORE the player is presented. During a presentation UIKit
    /// only accepts the current orientation, so a rotation requested from inside the player was refused
    /// (logged) and the player showed one portrait frame. Returns once the window is landscape (≤ ~0.6 s).
    @MainActor static func rotateForPlayer() async {
        guard UIDevice.current.userInterfaceIdiom == .phone else { return }
        if isLandscape { orientationLock = .landscape; return } // keep the current side, just lock it
        lockOrientation(.landscape, rotateTo: .landscapeRight)
        for _ in 0..<40 where !isLandscape {
            try? await Task.sleep(for: .milliseconds(15))
        }
    }

    /// Inside the player: lock to landscape; ask for a rotation only if the interface isn't landscape yet
    /// (a player not opened through `AppModel.play`), never for a side change during the presentation.
    @MainActor static func lockPlayerOrientation() {
        guard UIDevice.current.userInterfaceIdiom == .phone else { return }
        if isLandscape { orientationLock = .landscape } else { lockOrientation(.landscape, rotateTo: .landscapeRight) }
    }

    static func lockOrientation(_ mask: UIInterfaceOrientationMask, rotateTo orientation: UIInterfaceOrientationMask? = nil) {
        orientationLock = mask
        guard UIDevice.current.userInterfaceIdiom == .phone,
              let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        // The view controllers must learn the new mask BEFORE the geometry request, otherwise the request is
        // checked against the old one and refused (e.g. back to portrait after the landscape-only player).
        var vc = scene.keyWindow?.rootViewController
        while let current = vc {
            current.setNeedsUpdateOfSupportedInterfaceOrientations()
            vc = current.presentedViewController
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: orientation ?? mask)) { _ in }
    }
}
#endif
