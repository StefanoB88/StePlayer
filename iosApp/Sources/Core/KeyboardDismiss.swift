#if os(iOS)
import UIKit

/// App-wide "tap outside to close the keyboard".
///
/// A tap recognizer is attached to every window (sheets and full-screen covers live in the same
/// window, so they're covered too). It never swallows touches: buttons, lists and links keep
/// working, and taps on another text field just move the focus without the keyboard flickering.
@MainActor
enum KeyboardDismiss {
    private static let delegate = Delegate()
    private static var observer: NSObjectProtocol?

    static func install() {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(forName: UIWindow.didBecomeKeyNotification, object: nil, queue: .main) { note in
            guard let window = note.object as? UIWindow else { return }
            MainActor.assumeIsolated { attach(to: window) }
        }
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .forEach(attach(to:))
    }

    private static func attach(to window: UIWindow) {
        if window.gestureRecognizers?.contains(where: { $0.name == recognizerName }) == true { return }
        let tap = UITapGestureRecognizer(target: window, action: #selector(UIView.endEditing(_:)))
        tap.name = recognizerName
        tap.cancelsTouchesInView = false   // the tapped control still receives the touch
        tap.delaysTouchesEnded = false
        tap.delegate = delegate
        window.addGestureRecognizer(tap)
    }

    private static let recognizerName = "steplayer.keyboard-dismiss"

    private final class Delegate: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            // Only when a keyboard is up, and never for taps on a text input itself.
            var view = touch.view
            while let v = view {
                if v is UITextField || v is UITextView || v is UISearchBar { return false }
                view = v.superview
            }
            return true
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }
    }
}
#endif
