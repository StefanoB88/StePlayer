import ImageIO
import SwiftUI
import UIKit

/// Fast image loading for huge IPTV catalogs:
/// URLCache-backed disk cache + decoded in-memory cache (cost-limited) + ImageIO downsampling to the
/// displayed size, decoded off the main thread. Identical requests share one download, a download is
/// cancelled when every view waiting for it went away, and broken URLs are not retried for a while.
final class ImagePipeline: @unchecked Sendable {
    static let shared = ImagePipeline()

    private let memory = NSCache<NSString, UIImage>()
    private let session: URLSession
    private let lock = NSLock()
    private var inflight: [String: Entry] = [:]
    /// URL → time of the last failure (404, not an image, ...). Guarded by `lock`.
    private var failures: [String: Date] = [:]
    private var nextToken = 0
    private static let failureTTL: TimeInterval = 300

    private final class Entry {
        let token: Int
        var task: Task<UIImage?, Never>?
        var waiters = 0
        init(token: Int) { self.token = token }
    }

    private init() {
        memory.totalCostLimit = 96 * 1024 * 1024
        let config = URLSessionConfiguration.default
        // Decoded bitmaps live in `memory`; the URL cache only needs a small RAM share (disk does the work).
        config.urlCache = URLCache(memoryCapacity: 8 * 1024 * 1024, diskCapacity: 512 * 1024 * 1024)
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 20
        config.httpMaximumConnectionsPerHost = 6
        session = URLSession(configuration: config)
        NotificationCenter.default.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil) { [weak self] _ in
            self?.memory.removeAllObjects()
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil) { [weak self] _ in
            // Smaller footprint while suspended = less likely to be killed; lists refill from the disk cache.
            self?.memory.removeAllObjects()
        }
    }

    private func key(_ url: String, _ px: Int) -> NSString { "\(url)#\(px)" as NSString }

    /// Pixel bucket for a requested size (sizes 32 px apart share one decoded image).
    static func bucket(_ maxPixel: CGFloat) -> Int { max(32, Int((maxPixel / 32).rounded(.up)) * 32) }

    func cached(_ url: String, maxPixel: CGFloat) -> UIImage? {
        memory.object(forKey: key(url, Self.bucket(maxPixel)))
    }

    func image(_ urlString: String, maxPixel: CGFloat) async -> UIImage? {
        let px = Self.bucket(maxPixel)
        let k = key(urlString, px)
        if let hit = memory.object(forKey: k) { return hit }
        guard let url = URL(string: urlString), url.scheme?.hasPrefix("http") == true else { return nil }

        let entry: Entry? = lock.withLock {
            if let failed = failures[urlString], Date().timeIntervalSince(failed) < Self.failureTTL { return nil }
            if let running = inflight[k as String] {
                running.waiters += 1
                return running
            }
            nextToken += 1
            let fresh = Entry(token: nextToken)
            fresh.waiters = 1
            fresh.task = Task.detached(priority: .utility) { [weak self] in
                await self?.load(url, urlString: urlString, px: px, key: k, token: fresh.token)
            }
            inflight[k as String] = fresh
            return fresh
        }
        guard let entry, let task = entry.task else { return nil }
        return await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            self.leave(entry, key: k as String)
        }
    }

    /// A waiting view went away; the download stops when nobody is waiting any more.
    private func leave(_ entry: Entry, key: String) {
        lock.withLock {
            entry.waiters -= 1
            guard entry.waiters <= 0 else { return }
            entry.task?.cancel()
            if inflight[key]?.token == entry.token { inflight[key] = nil }
        }
    }

    private func load(_ url: URL, urlString: String, px: Int, key k: NSString, token: Int) async -> UIImage? {
        defer { lock.withLock { if inflight[k as String]?.token == token { inflight[k as String] = nil } } }
        do {
            let (data, response) = try await session.data(from: url)
            try Task.checkCancellation()
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                markFailed(urlString)
                return nil
            }
            guard let image = Self.downsample(data, maxPixel: CGFloat(px)) else {
                markFailed(urlString)
                return nil
            }
            memory.setObject(image, forKey: k, cost: Self.cost(of: image))
            return image
        } catch {
            let cancelled = error is CancellationError || (error as? URLError)?.code == .cancelled
            if !cancelled { markFailed(urlString) }
            return nil
        }
    }

    private func markFailed(_ url: String) {
        lock.withLock {
            if failures.count > 2000 { failures.removeAll() }
            failures[url] = Date()
        }
    }

    private static func cost(of image: UIImage) -> Int {
        if let cg = image.cgImage { return cg.bytesPerRow * cg.height }
        return Int(image.size.width * image.size.height * image.scale * image.scale * 4)
    }

    /// Decodes straight to a thumbnail no larger than `maxPixel` (never the full-size bitmap).
    private static func downsample(_ data: Data, maxPixel: CGFloat) -> UIImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options) else { return nil }
        let thumbOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true, // decode now, on this background thread
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ] as CFDictionary
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOptions) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Async image with caching, downsampling and a tasteful placeholder.
struct CachedImage<Placeholder: View>: View {
    let url: String?
    var maxPixel: CGFloat = 600
    var contentMode: ContentMode = .fill
    @ViewBuilder var placeholder: () -> Placeholder

    private struct Loaded { let url: String; let image: UIImage }
    @State private var loaded: Loaded?

    /// The image for the CURRENT url: what this view loaded, else a synchronous memory-cache hit
    /// (no placeholder flash when a recycled cell scrolls back into view), never a stale one.
    private var image: UIImage? {
        guard let url, !url.isEmpty else { return nil }
        if let loaded, loaded.url == url { return loaded.image }
        return ImagePipeline.shared.cached(url, maxPixel: maxPixel)
    }

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
                    .transition(.opacity)
            } else {
                placeholder()
            }
        }
        .task(id: url) {
            guard let url, !url.isEmpty else { loaded = nil; return }
            if let hit = ImagePipeline.shared.cached(url, maxPixel: maxPixel) {
                loaded = Loaded(url: url, image: hit)
                return
            }
            // Cancelled automatically when the cell disappears; the pipeline then drops the download.
            guard let image = await ImagePipeline.shared.image(url, maxPixel: maxPixel), !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.2)) { loaded = Loaded(url: url, image: image) }
        }
    }
}

extension CachedImage where Placeholder == ArtworkPlaceholder {
    init(url: String?, maxPixel: CGFloat = 600, contentMode: ContentMode = .fill, symbol: String = "film") {
        self.init(url: url, maxPixel: maxPixel, contentMode: contentMode) { ArtworkPlaceholder(symbol: symbol) }
    }
}

struct ArtworkPlaceholder: View {
    var symbol: String = "film"
    var body: some View {
        ZStack {
            LinearGradient(colors: [Theme.surfaceElevated, Theme.surface], startPoint: .top, endPoint: .bottom)
            Image(systemName: symbol)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Theme.textSecondary.opacity(0.5))
        }
    }
}
