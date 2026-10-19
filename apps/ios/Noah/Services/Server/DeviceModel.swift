import Foundation

/// Model labels from expo-device 58.0.5 (MIT, © 2015-present 650 Industries, Inc.).
/// https://github.com/expo/expo/blob/expo-device%4058.0.5/packages/expo-device/ios/UIDevice.swift
/// Keep these labels stable across the RN → native update.
enum DeviceModel {
    static var current: String {
        var info = utsname()
        uname(&info)
        let identifier = withUnsafeBytes(of: &info.machine) { bytes in
            String(decoding: bytes.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        return name(for: identifier)
    }

    static func name(for identifier: String) -> String {
        switch identifier {
        case "iPod9,1": "iPod touch (7th generation)"
        case "iPhone8,1": "iPhone 6s"
        case "iPhone8,2": "iPhone 6s Plus"
        case "iPhone9,1", "iPhone9,3": "iPhone 7"
        case "iPhone9,2", "iPhone9,4": "iPhone 7 Plus"
        case "iPhone10,1", "iPhone10,4": "iPhone 8"
        case "iPhone10,2", "iPhone10,5": "iPhone 8 Plus"
        case "iPhone10,3", "iPhone10,6": "iPhone X"
        case "iPhone11,2": "iPhone XS"
        case "iPhone11,4", "iPhone11,6": "iPhone XS Max"
        case "iPhone11,8": "iPhone XR"
        case "iPhone12,1": "iPhone 11"
        case "iPhone12,3": "iPhone 11 Pro"
        case "iPhone12,5": "iPhone 11 Pro Max"
        case "iPhone13,1": "iPhone 12 mini"
        case "iPhone13,2": "iPhone 12"
        case "iPhone13,3": "iPhone 12 Pro"
        case "iPhone13,4": "iPhone 12 Pro Max"
        case "iPhone14,4": "iPhone 13 mini"
        case "iPhone14,5": "iPhone 13"
        case "iPhone14,2": "iPhone 13 Pro"
        case "iPhone14,3": "iPhone 13 Pro Max"
        case "iPhone14,7": "iPhone 14"
        case "iPhone14,8": "iPhone 14 Plus"
        case "iPhone15,2": "iPhone 14 Pro"
        case "iPhone15,3": "iPhone 14 Pro Max"
        case "iPhone15,4": "iPhone 15"
        case "iPhone15,5": "iPhone 15 Plus"
        case "iPhone16,1": "iPhone 15 Pro"
        case "iPhone16,2": "iPhone 15 Pro Max"
        case "iPhone17,3": "iPhone 16"
        case "iPhone17,4": "iPhone 16 Plus"
        case "iPhone17,1": "iPhone 16 Pro"
        case "iPhone17,2": "iPhone 16 Pro Max"
        case "iPhone17,5": "iPhone 16e"
        case "iPhone18,1": "iPhone 17 Pro"
        case "iPhone18,2": "iPhone 17 Pro Max"
        case "iPhone19,2": "iPhone 18 Pro"
        case "iPhone19,3", "iPhone19,7": "iPhone 18 Pro Max"
        case "iPhone19,4": "iPhone Duo"
        case "iPhone18,3": "iPhone 17"
        case "iPhone18,4": "iPhone Air"
        case "iPhone18,5": "iPhone 17e"
        case "iPhone8,4": "iPhone SE (1st generation)"
        case "iPhone12,8": "iPhone SE (2nd generation)"
        case "iPhone14,6": "iPhone SE (3rd generation)"
        case "iPad6,11", "iPad6,12": "iPad (5th generation)"
        case "iPad7,5", "iPad7,6": "iPad (6th generation)"
        case "iPad7,11", "iPad7,12": "iPad (7th generation)"
        case "iPad11,6", "iPad11,7": "iPad (8th generation)"
        case "iPad12,1", "iPad12,2": "iPad (9th generation)"
        case "iPad13,18", "iPad13,19": "iPad (10th generation)"
        case "iPad15,7", "iPad15,8": "iPad (A16)"
        case "iPad5,3", "iPad5,4": "iPad Air 2"
        case "iPad11,3", "iPad11,4": "iPad Air (3rd generation)"
        case "iPad13,1", "iPad13,2": "iPad Air (4th generation)"
        case "iPad13,16", "iPad13,17": "iPad Air (5th generation)"
        case "iPad14,8", "iPad14,9": "iPad Air 11-inch (M2)"
        case "iPad14,10", "iPad14,11": "iPad Air 13-inch (M2)"
        case "iPad15,3", "iPad15,4": "iPad Air 11-inch (M3)"
        case "iPad15,5", "iPad15,6": "iPad Air 13-inch (M3)"
        case "iPad16,8", "iPad16,9": "iPad Air 11-inch (M4)"
        case "iPad16,10", "iPad16,11": "iPad Air 13-inch (M4)"
        case "iPad5,1", "iPad5,2": "iPad mini 4"
        case "iPad11,1", "iPad11,2": "iPad mini (5th generation)"
        case "iPad14,1", "iPad14,2": "iPad mini (6th generation)"
        case "iPad16,1", "iPad16,2": "iPad mini (A17 Pro)"
        case "iPad6,3", "iPad6,4": "iPad Pro (9.7-inch)"
        case "iPad7,3", "iPad7,4": "iPad Pro (10.5-inch)"
        case "iPad8,1", "iPad8,2", "iPad8,3", "iPad8,4": "iPad Pro (11-inch) (1st generation)"
        case "iPad8,9", "iPad8,10": "iPad Pro (11-inch) (2nd generation)"
        case "iPad13,4", "iPad13,5", "iPad13,6", "iPad13,7": "iPad Pro (11-inch) (3rd generation)"
        case "iPad14,3-A", "iPad14,3-B", "iPad14,4-A", "iPad14,4-B": "iPad Pro (11-inch) (4th generation)"
        case "iPad6,7", "iPad6,8": "iPad Pro (12.9-inch) (1st generation)"
        case "iPad7,1", "iPad7,2": "iPad Pro (12.9-inch) (2nd generation)"
        case "iPad8,5", "iPad8,6", "iPad8,7", "iPad8,8": "iPad Pro (12.9-inch) (3rd generation)"
        case "iPad8,11", "iPad8,12": "iPad Pro (12.9-inch) (4th generation)"
        case "iPad13,8", "iPad13,9", "iPad13,10", "iPad13,11": "iPad Pro (12.9-inch) (5th generation)"
        case "iPad14,5-A", "iPad14,5-B", "iPad14,6-A", "iPad14,6-B": "iPad Pro (12.9-inch) (6th generation)"
        case "iPad16,3-A", "iPad16,3-B", "iPad16,4-A", "iPad16,4-B": "iPad Pro 11-inch (M4)"
        case "iPad16,5-A", "iPad16,5-B", "iPad16,6-A", "iPad16,6-B": "iPad Pro 13-inch (M4)"
        case "iPad17,1-A", "iPad17,1-B", "iPad17,2-A", "iPad17,2-B": "iPad Pro 11-inch (M5)"
        case "iPad17,3-A", "iPad17,3-B", "iPad17,4-A", "iPad17,4-B": "iPad Pro 13-inch (M5)"
        case "i386", "x86_64", "arm64": "Simulator iOS"
        default: identifier
        }
    }
}
