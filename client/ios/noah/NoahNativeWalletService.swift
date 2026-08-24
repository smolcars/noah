import Foundation
import Security

struct NoahNativeWalletBalance {
  let onchain: NoahArkOnchainBalance
  let offchain: NoahArkOffchainBalance

  var total: UInt64 {
    onchain.confirmed
      + onchain.immature
      + onchain.trustedPending
      + onchain.untrustedPending
      + offchain.pendingExit
      + offchain.pendingLightningSend
      + offchain.pendingInRound
      + offchain.spendable
      + offchain.pendingBoard
  }
}

enum NoahNativeWalletError: LocalizedError {
  case invalidAppVariant
  case documentsDirectoryUnavailable
  case mnemonicUnavailable(OSStatus)
  case invalidMnemonic

  var errorDescription: String? {
    switch self {
    case .invalidAppVariant:
      return "Noah's wallet network is unavailable."
    case .documentsDirectoryUnavailable:
      return "Noah's wallet storage is unavailable."
    case .mnemonicUnavailable(let status):
      if status == errSecItemNotFound {
        return "No Noah wallet was found on this device."
      }
      return "Noah could not access the wallet key."
    case .invalidMnemonic:
      return "Noah's wallet key is invalid."
    }
  }
}

actor NoahNativeWalletService {
  static let shared = NoahNativeWalletService()

  private init() {}

  func loadWallet() throws {
    guard !NoahArkBridge.isWalletLoaded() else {
      return
    }

    let variant = try appVariant()
    let mnemonic = try readMnemonic(for: variant)

    try NoahArkBridge.loadWallet(
      atPath: try walletDataDirectory(for: variant),
      mnemonic: mnemonic,
      configuration: walletConfiguration(for: variant)
    )
  }

  func sync() throws {
    try NoahArkBridge.sync()
  }

  func onchainSync() throws {
    try NoahArkBridge.onchainSync()
  }

  func offchainBalance() throws -> NoahArkOffchainBalance {
    try NoahArkBridge.offchainBalance()
  }

  func onchainBalance() throws -> NoahArkOnchainBalance {
    try NoahArkBridge.onchainBalance()
  }

  func newAddress() throws -> String {
    try loadWallet()
    return try NoahArkBridge.newAddress()
  }

  func bolt11Invoice(
    amountSat: UInt64,
    description: String? = nil,
    token: String? = nil
  ) throws -> NoahArkBolt11Invoice {
    try loadWallet()
    return try NoahArkBridge.bolt11Invoice(
      forAmountSat: amountSat,
      description: description,
      token: token
    )
  }

  func refreshBalance() throws -> NoahNativeWalletBalance {
    try loadWallet()
    try sync()
    try onchainSync()

    return try NoahNativeWalletBalance(
      onchain: onchainBalance(),
      offchain: offchainBalance()
    )
  }

  private func appVariant() throws -> String {
    guard let variant = Bundle.main.object(forInfoDictionaryKey: "APP_VARIANT") as? String,
      ["mainnet", "signet", "regtest"].contains(variant)
    else {
      throw NoahNativeWalletError.invalidAppVariant
    }
    return variant
  }

  private func walletDataDirectory(for variant: String) throws -> String {
    guard let documentsDirectory = FileManager.default.urls(
      for: .documentDirectory,
      in: .userDomainMask
    ).first else {
      throw NoahNativeWalletError.documentsDirectoryUnavailable
    }

    return documentsDirectory
      .appendingPathComponent("noah-data-\(variant)", isDirectory: true)
      .path
  }

  private func readMnemonic(for variant: String) throws -> String {
    let query: [CFString: Any] = [
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: "com.noah.mnemonic.\(variant)",
      kSecAttrAccount: "noah",
      kSecReturnData: true,
      kSecMatchLimit: kSecMatchLimitOne,
    ]

    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    guard status == errSecSuccess else {
      throw NoahNativeWalletError.mnemonicUnavailable(status)
    }
    guard let data = item as? Data,
      let mnemonic = String(data: data, encoding: .utf8),
      !mnemonic.isEmpty
    else {
      throw NoahNativeWalletError.invalidMnemonic
    }
    return mnemonic
  }

  private func walletConfiguration(for variant: String) -> NoahArkWalletConfiguration {
    let configuration = NoahArkWalletConfiguration()
    let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
      ?? "unknown"

    configuration.userAgent = "noah-\(variant)-ios/\(version)"
    configuration.fallbackFeeRate = 10_000
    configuration.htlcReceiveClaimDelta = 18
    configuration.vtxoExitMargin = 12

    switch variant {
    case "mainnet":
      configuration.bitcoin = true
      configuration.arkURL = "https://ark.second.tech"
      configuration.esploraURL = effectiveEsploraURL(
        for: variant,
        defaultURL: "https://mempool.second.tech/api"
      )
      configuration.vtxoRefreshExpiryThreshold = 144
      configuration.roundTransactionRequiredConfirmations = 2
    case "signet":
      configuration.signet = true
      configuration.arkURL = "https://ark.signet.2nd.dev"
      configuration.esploraURL = effectiveEsploraURL(
        for: variant,
        defaultURL: "https://esplora.signet.2nd.dev"
      )
      configuration.vtxoRefreshExpiryThreshold = 12
      configuration.roundTransactionRequiredConfirmations = 1
    case "regtest":
      configuration.regtest = true
      configuration.arkURL = "http://localhost:3535"
      configuration.bitcoindURL = "http://localhost:18443"
      configuration.bitcoindUser = "second"
      configuration.bitcoindPassword = "ark"
      configuration.vtxoRefreshExpiryThreshold = 24
      configuration.roundTransactionRequiredConfirmations = 1
    default:
      break
    }

    return configuration
  }

  private func effectiveEsploraURL(for variant: String, defaultURL: String) -> String {
    UserDefaults.standard.string(forKey: "noah.native.esplora.\(variant)") ?? defaultURL
  }
}
