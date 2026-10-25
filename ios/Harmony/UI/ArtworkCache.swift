import ImageIO
import UIKit

/**
 * Cover art from the artwork folder, decoded once and kept in memory.
 *
 * Views redraw often (the progress line four times a second, the record at
 * 60 fps); reading a JPEG from disk on each of those would stutter. Images
 * are downsampled to the size they are shown at (at most 600 px), so a long
 * library list stays light.
 */
final class ArtworkCache: @unchecked Sendable {
    static let shared = ArtworkCache()

    private let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 400
        return cache
    }()

    /// The image at [url], at most [maxPixels] on its longer side.
    func image(for url: URL?, maxPixels: CGFloat = 300) -> UIImage? {
        guard let url else { return nil }
        let bucket = maxPixels > 300 ? 600 : 300
        let key = "\(url.path)#\(bucket)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        guard let image = Self.downsample(url, maxPixels: CGFloat(bucket)) else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }

    /// Forgets a song's artwork (it was replaced).
    func forget(_ url: URL) {
        cache.removeObject(forKey: "\(url.path)#300" as NSString)
        cache.removeObject(forKey: "\(url.path)#600" as NSString)
    }

    private static func downsample(_ url: URL, maxPixels: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
