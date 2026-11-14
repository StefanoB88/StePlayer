import Foundation
import Shared

/// Owns a Kotlin flow subscription; cancels it when released.
final class FlowSubscription {
    private var closeable: Closeable?
    private var onCancel: (() -> Void)?
    init(_ closeable: Closeable, onCancel: (() -> Void)? = nil) {
        self.closeable = closeable
        self.onCancel = onCancel
    }
    func cancel() {
        closeable?.close()
        closeable = nil
        onCancel?()
        onCancel = nil
    }
    deinit {
        closeable?.close()
        onCancel?()
    }
}

/// Observes a Kotlin `CFlow`. Values arrive on the main thread.
func observe<T: AnyObject>(_ flow: CFlow<T>, _ onEach: @escaping (T) -> Void) -> FlowSubscription {
    FlowSubscription(flow.watch { (value: T?) in
        if let value { onEach(value) }
    })
}

/// Observes a `CFlow<List<E>>` as a Swift array (converted on the main thread: small lists only).
func observeList<E>(_ flow: CFlow<NSArray>, as _: E.Type = E.self, _ onEach: @escaping ([E]) -> Void) -> FlowSubscription {
    observe(flow) { array in onEach(array as? [E] ?? []) }
}

/// Observes a `CFlow<List<E>>` and converts every emission OFF the main thread.
///
/// Big catalogs (thousands of channels / tens of thousands of movies) make the NSArray → [E] bridge and the
/// Kotlin property reads (every `String` is converted on access) expensive, so `transform` runs on a serial
/// background queue (Kotlin/Native objects are safe to read from any thread with the new memory model).
/// Results reach `onEach` on the main thread, in order; emissions superseded while converting are skipped,
/// and a result equal to the previously delivered one is dropped (no pointless SwiftUI invalidation).
func observeListOffMain<E, R: Equatable>(_ flow: CFlow<NSArray>, as _: E.Type = E.self,
                                         transform: @escaping ([E]) -> R,
                                         _ onEach: @escaping @MainActor (R) -> Void) -> FlowSubscription {
    let pipe = OffMainPipe<R>()
    let closeable = flow.watch { (value: NSArray?) in
        guard let value else { return }
        let seq = pipe.next()
        let box = UncheckedBox(value)
        pipe.queue.async {
            guard pipe.isLatest(seq) else { return } // a newer emission is already waiting
            let converted = transform(box.value as? [E] ?? [])
            // Compared here, off the main thread; the serial queue + FIFO main hops keep results in order.
            guard pipe.isNew(converted) else { return }
            let result = UncheckedBox(converted)
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard !pipe.isCancelled else { return }
                    onEach(result.value)
                }
            }
        }
    }
    return FlowSubscription(closeable, onCancel: { pipe.cancel() })
}

/// Runs `work` on a background queue and hands the result back on the main actor.
func offMain<R>(qos: DispatchQoS.QoSClass = .userInitiated, _ work: @escaping () -> R) async -> R {
    await withCheckedContinuation { (cont: CheckedContinuation<UncheckedBox<R>, Never>) in
        DispatchQueue.global(qos: qos).async { cont.resume(returning: UncheckedBox(work())) }
    }.value
}

/// Carries non-Sendable values (Kotlin objects, immutable snapshots) across the background hop.
struct UncheckedBox<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}

/// Sequencing state for `observeListOffMain`.
private final class OffMainPipe<R: Equatable>: @unchecked Sendable {
    let queue = DispatchQueue(label: "steplayer.flow-convert", qos: .userInitiated)
    private let lock = NSLock()
    private var latest = 0
    private var cancelled = false
    /// Last result handed to the main thread. Touched only on `queue`.
    private var last: R?

    func next() -> Int { lock.withLock { latest += 1; return latest } }
    func isLatest(_ seq: Int) -> Bool { lock.withLock { seq == latest && !cancelled } }
    var isCancelled: Bool { lock.withLock { cancelled } }
    func cancel() { lock.withLock { cancelled = true } }

    /// On `queue`: true (and remembered) when `value` differs from the previous result.
    func isNew(_ value: R) -> Bool {
        if let last, last == value { return false }
        last = value
        return true
    }
}

/// Human readable message from a Kotlin exception bridged as NSError.
func readableError(_ error: Error) -> String {
    let ns = error as NSError
    if let kotlin = ns.userInfo["KotlinException"] as? KotlinThrowable, let message = kotlin.message, !message.isEmpty {
        return message
    }
    if ns.domain == NSURLErrorDomain { return "Errore di rete: \(ns.localizedDescription)" }
    return ns.localizedDescription
}

extension Int64 {
    /// Current time in epoch milliseconds (Kotlin side uses millis everywhere).
    static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Keeps `subscriptions` alive until the calling task is cancelled, then closes them.
///
/// Use from a SwiftUI `.task {}` instead of storing a subscription in `@State`: a closure stored in the
/// view's own state captures that state (subscription → closure → view state → subscription), a cycle
/// that keeps the Kotlin collector running after the screen is gone.
func holdUntilCancelled(_ subscriptions: [FlowSubscription]) async {
    while !Task.isCancelled {
        try? await Task.sleep(for: .seconds(3600))
    }
    subscriptions.forEach { $0.cancel() }
}

/// First emission of a list flow, then unsubscribes (one-shot reads such as "channels of this category").
/// Returns `[]` if the calling task is cancelled before anything arrives.
@MainActor
func firstList<E>(_ flow: CFlow<NSArray>, as _: E.Type = E.self) async -> [E] {
    let state = FirstValueState<[E]>()
    let value = await withTaskCancellationHandler {
        await withCheckedContinuation { (cont: CheckedContinuation<[E], Never>) in
            guard state.setContinuation(cont) else { return }
            state.subscription = observeList(flow, as: E.self) { list in state.resume(list) }
        }
    } onCancel: {
        state.resume([])
    }
    state.subscription?.cancel()
    state.subscription = nil
    return value
}

private final class FirstValueState<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Never>?
    private var finished = false
    var subscription: FlowSubscription?

    /// False when cancelled before the continuation was installed (it has then been resumed already).
    func setContinuation(_ c: CheckedContinuation<T, Never>) -> Bool {
        lock.lock()
        if finished { lock.unlock(); c.resume(returning: pendingValue!); return false }
        continuation = c
        lock.unlock()
        return true
    }

    private var pendingValue: T?

    func resume(_ value: T) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        let c = continuation
        continuation = nil
        if c == nil { pendingValue = value }
        lock.unlock()
        c?.resume(returning: value)
    }
}

/// A list published to SwiftUI by identity. `@Observable` compares Equatable values before notifying, which for
/// thousands of rows would be an O(n) comparison on the main thread at every update; the producers here
/// already publish only real changes (compared in the background), so a cheap token comparison is enough.
struct ListSnapshot<Element>: Equatable {
    let items: [Element]
    private let token: Int

    init(_ items: [Element]) {
        self.items = items
        token = snapshotTokens.next()
    }

    static func == (a: Self, b: Self) -> Bool { a.token == b.token }
}

private let snapshotTokens = TokenCounter()

private final class TokenCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    func next() -> Int { lock.withLock { value += 1; return value } }
}
