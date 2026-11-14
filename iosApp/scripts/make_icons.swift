// Renders StePlayer app icons (play triangle on the accent gradient) with CoreGraphics.
// Usage: swift scripts/make_icons.swift <output-dir> <name>:<width>x<height>:<layer> ...
// layer = full | back | front
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

func color(_ hex: UInt32) -> CGColor {
    CGColor(red: CGFloat((hex >> 16) & 0xff) / 255, green: CGFloat((hex >> 8) & 0xff) / 255, blue: CGFloat(hex & 0xff) / 255, alpha: 1)
}

func render(width: Int, height: Int, layer: String) -> CGImage {
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let w = CGFloat(width), h = CGFloat(height)
    if layer != "front" {
        // Background: deep navy with the violet → cyan gradient glow.
        ctx.setFillColor(color(0x0B0D12)); ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        let gradient = CGGradient(colorsSpace: space, colors: [color(0x7C5CFF), color(0x00D4FF)] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: h), end: CGPoint(x: w, y: 0), options: [])
        let vignette = CGGradient(colorsSpace: space, colors: [CGColor(gray: 0, alpha: 0), CGColor(gray: 0, alpha: 0.35)] as CFArray, locations: [0, 1])!
        ctx.drawRadialGradient(vignette, startCenter: CGPoint(x: w / 2, y: h / 2), startRadius: 0,
                               endCenter: CGPoint(x: w / 2, y: h / 2), endRadius: max(w, h) * 0.75, options: [.drawsAfterEndLocation])
    }
    if layer != "back" {
        // Rounded play triangle.
        let s = min(w, h) * 0.42
        let cx = w / 2 + s * 0.08, cy = h / 2
        let p1 = CGPoint(x: cx - s * 0.45, y: cy + s * 0.55)
        let p2 = CGPoint(x: cx - s * 0.45, y: cy - s * 0.55)
        let p3 = CGPoint(x: cx + s * 0.55, y: cy)
        let path = CGMutablePath()
        let r = s * 0.12
        path.move(to: CGPoint(x: (p1.x + p2.x) / 2, y: (p1.y + p2.y) / 2))
        path.addArc(tangent1End: p2, tangent2End: p3, radius: r)
        path.addArc(tangent1End: p3, tangent2End: p1, radius: r)
        path.addArc(tangent1End: p1, tangent2End: p2, radius: r)
        path.closeSubpath()
        ctx.setShadow(offset: CGSize(width: 0, height: -s * 0.04), blur: s * 0.12, color: CGColor(gray: 0, alpha: 0.35))
        ctx.setFillColor(CGColor(gray: 1, alpha: 1))
        ctx.addPath(path); ctx.fillPath()
    }
    return ctx.makeImage()!
}

let args = CommandLine.arguments
let out = URL(fileURLWithPath: args[1])
for spec in args.dropFirst(2) {
    let parts = spec.split(separator: ":")
    let dims = parts[1].split(separator: "x").map { Int($0)! }
    let image = render(width: dims[0], height: dims[1], layer: String(parts[2]))
    let url = out.appendingPathComponent(String(parts[0]))
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
}
