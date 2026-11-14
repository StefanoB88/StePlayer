#if DEBUG && os(iOS)
import UIKit

/// Development only (never in release builds): `STEPLAYER_DEMO_AUTOSCROLL=<points per second>` scrolls the
/// biggest list on screen up and down for 20 s and writes frame statistics (hitches, memory footprint) to
/// `tmp/autoscroll.txt` in the app container. Used to profile big catalogs on the simulator without touches.
@MainActor
enum DemoAutoScroll {
    static func runIfRequested() {
        let env = ProcessInfo.processInfo.environment
        guard let speed = env["STEPLAYER_DEMO_AUTOSCROLL"].flatMap(Double.init) else { return }
        let delay = env["STEPLAYER_DEMO_AUTOSCROLL_DELAY"].flatMap(Double.init) ?? 8
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(delay))
            runner = Runner(speed: speed)
            runner?.start()
        }
    }

    private static var runner: Runner?

    @MainActor private final class Runner: NSObject {
        let speed: Double
        var scrollView: UIScrollView?
        var link: CADisplayLink?
        var direction = 1.0
        var startTime: CFTimeInterval = 0
        var last: CFTimeInterval = 0
        var frames = 0
        var hitches = 0
        var hitchTime: CFTimeInterval = 0
        var worst: CFTimeInterval = 0
        let duration: CFTimeInterval = 20

        init(speed: Double) { self.speed = speed }

        func start() {
            let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
            var all: [UIScrollView] = []
            func walk(_ v: UIView) {
                if let s = v as? UIScrollView, s.contentSize.height > s.bounds.height * 2 { all.append(s) }
                v.subviews.forEach(walk)
            }
            windows.forEach(walk)
            // Ties (synchronised columns): `STEPLAYER_DEMO_AUTOSCROLL_PICK=last` takes the right-most one.
            let pickLast = ProcessInfo.processInfo.environment["STEPLAYER_DEMO_AUTOSCROLL_PICK"] == "last"
            scrollView = all.max { pickLast ? $0.contentSize.height <= $1.contentSize.height : $0.contentSize.height < $1.contentSize.height }
            guard scrollView != nil else { write("no scroll view"); return }
            let link = CADisplayLink(target: self, selector: #selector(step(_:)))
            link.add(to: .main, forMode: .common)
            self.link = link
        }

        @objc func step(_ l: CADisplayLink) {
            guard let s = scrollView else { return }
            if last == 0 { last = l.timestamp; startTime = l.timestamp; return }
            let dt = l.timestamp - last
            let expected = max(1.0 / 120, l.targetTimestamp - l.timestamp)
            frames += 1
            if dt > expected * 1.5 { hitches += 1; hitchTime += dt - expected }
            worst = max(worst, dt)
            last = l.timestamp
            let maxY = max(0, s.contentSize.height - s.bounds.height)
            var y = s.contentOffset.y + direction * speed * dt
            if y >= maxY { y = maxY; direction = -1 } else if y <= 0 { y = 0; direction = 1 }
            s.contentOffset = CGPoint(x: s.contentOffset.x, y: y)
            if l.timestamp - startTime >= duration {
                l.invalidate()
                let total = l.timestamp - startTime
                write(String(format: "frames %d  hitches %d  hitch-time %.0f ms (%.1f ms/s)  worst frame %.0f ms  footprint %.0f MB",
                             frames, hitches, hitchTime * 1000, hitchTime * 1000 / total, worst * 1000, footprintMB()))
            }
        }

        func write(_ text: String) {
            let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("autoscroll.txt")
            try? (text + "\n").write(to: url, atomically: true, encoding: .utf8)
            print("AUTOSCROLL", text)
        }

        func footprintMB() -> Double {
            var info = task_vm_info_data_t()
            var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
            let kr = withUnsafeMutablePointer(to: &info) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
            }
            return kr == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576 : -1
        }
    }
}
#endif
