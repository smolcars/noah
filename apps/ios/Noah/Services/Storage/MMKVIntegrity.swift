import Foundation
import zlib

/// MMKV 2.4.3 Core/MMKVMetaInfo.hpp and MMKV_IO.cpp; never maps or recovers files.
enum MMKVIntegrity {
    static func verifies(root: URL, store: String) -> Bool {
        let file = root.appending(path: store)
        guard FileManager.default.fileExists(atPath: file.path) else { return true }
        guard let data = try? Data(contentsOf: file),
              let meta = try? Data(contentsOf: root.appending(path: store + ".crc")) else { return false }
        return verifies(data: data, meta: meta)
    }

    static func verifies(data: Data, meta: Data) -> Bool {
        // 112-byte little-endian struct, including IV, confirmed metadata, padding and flags.
        guard data.count >= 4, meta.count >= 112 else { return false }
        func u32(_ bytes: Data, _ offset: Int) -> UInt32 {
            (0..<4).reduce(0) { $0 | UInt32(bytes[offset + $1]) << (8 * $1) }
        }
        let version = u32(meta, 4)
        guard version <= 4 else { return false } // 5/6 reserved, 7 is the version holder.
        let size = Int(version >= 3 ? u32(meta, 28) : u32(data, 0))
        guard size <= data.count - 4 else { return false }
        // The v3+ header and last-confirmed pair may legitimately lag current metadata.
        let digest = data.withUnsafeBytes { bytes in
            bytes.bindMemory(to: Bytef.self).baseAddress.map { crc32(0, $0.advanced(by: 4), uInt(size)) }
        }
        guard let digest else { return false }
        return digest == u32(meta, 0)
    }
}
