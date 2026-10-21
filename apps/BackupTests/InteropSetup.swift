import Foundation

// Compiled alongside the pinned SDK's Swift sources, against its macOS binary.
// Only test setup and server authentication run here; interop runs in the apps.
@main struct InteropSetup {
    static func main() async {
        do {
            let input = FileHandle.standardInput.readDataToEndOfFile()
            guard let phrase = String(data: input, encoding: .utf8), !phrase.isEmpty else { exit(2) }
            if CommandLine.arguments[1] == "login" {
                let base = URL(string: "http://127.0.0.1:3100/v0/")!
                let (challenge, _) = try await URLSession.shared.data(from: base.appending(path: "getk1"))
                let k1 = (try JSONSerialization.jsonObject(with: challenge) as! [String: Any])["k1"] as! String
                let key = try derivePublicKeyFromMnemonic(mnemonic: phrase, network: .regtest, index: 0)
                let signature = try signMessageWithMnemonic(message: k1, mnemonic: phrase, network: .regtest, index: 0)
                var request = URLRequest(url: base.appending(path: "auth/login"))
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = try JSONSerialization.data(withJSONObject: ["k1": k1, "key": key, "sig": signature])
                let (data, response) = try await URLSession.shared.data(for: request)
                guard (response as? HTTPURLResponse)?.statusCode == 200,
                      let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let token = object["access_token"] as? String else { exit(3) }
                // Private pipe to the runner; never inherited stdout or evidence.
                FileHandle.standardOutput.write(Data(token.utf8))
            } else {
                let root = URL(fileURLWithPath: CommandLine.arguments[2])
                let config = Config(serverAddress: "http://127.0.0.1:3535", bitcoindAddress: "http://127.0.0.1:18443",
                                    bitcoindUser: "second", bitcoindPass: "ark", fallbackFeeRate: 2500,
                                    roundTxRequiredConfirmations: 1)
                let inspect = CommandLine.arguments[1] == "inspect"
                let onchain = inspect
                    ? try await OnchainWallet.openExisting(network: .regtest, mnemonic: phrase, config: config, datadir: root.path)
                    : try await OnchainWallet.default(network: .regtest, mnemonic: phrase, config: config, datadir: root.path)
                let wallet = try await Wallet.openWithOnchain(network: .regtest, mnemonicOrSeed: phrase, config: config,
                    args: WalletOpenArgs(runDaemon: false, datadir: root.path, createIfNotExists: !inspect,
                                         createWithoutServer: false, skipRecovery: true), onchain: onchain)
                if inspect {
                    let ark = try await wallet.balance(), chain = try await onchain.balance()
                    let rows: [(String, UInt64)] = [
                        ("Onchain", chain.totalSats), ("Confirmed", chain.confirmedSats), ("Pending", chain.pendingSats),
                        ("Immature", chain.totalSats - chain.confirmedSats - chain.pendingSats),
                        ("Offchain", ark.totalSats), ("Spendable", ark.spendableSats), ("Needs Refresh", ark.needsRefreshSats),
                        ("Pending Send", ark.pendingLightningSendSats), ("Claimable Lightning Receive", ark.claimableLightningReceiveSats),
                        ("Pending In Round", ark.pendingInRoundSats), ("Pending Exit", ark.pendingExitSats), ("Pending Board", ark.pendingBoardSats),
                        ("Pending Arkoor Send", ark.pendingArkoorSendSats), ("Pending Offboard", ark.pendingOffboardSats)]
                    let result: [String: Any] = ["fingerprint": wallet.fingerprint(),
                        "balance": rows.map { ["label": $0.0, "sats": $0.1] as [String: Any] },
                        "movementCount": try await wallet.history().count,
                        "onchainHistory": try await onchain.transactions().filter { $0.balanceChangeSats != 0 }.map {
                            ["id": "onchain-wallet-\($0.txid)", "amount": $0.balanceChangeSats,
                             "confirmed": $0.confirmation != nil] as [String: Any]
                        }.sorted { ($0["id"] as! String) < ($1["id"] as! String) }]
                    FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: result))
                    return
                }
                let snapshot = try await wallet.createSnapshot(destinationPath: root.appending(path: "snapshot.sqlite").path)
                let manifest: [String: Any] = ["formatVersion": 2, "createdAt": ISO8601DateFormatter().string(from: Date()),
                    "snapshotSha256": snapshot.sha256, "network": snapshot.network, "walletFingerprint": snapshot.walletFingerprint,
                    "serverPubkey": snapshot.serverPubkey as Any? ?? NSNull(), "mailboxPubkey": snapshot.mailboxPubkey as Any? ?? NSNull(),
                    "barkSchemaVersion": snapshot.schemaVersion]
                try JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys]).write(to: root.appending(path: "manifest.json"))
            }
        } catch {
            // SDK/network errors may carry private inputs. Keep diagnostics fixed.
            FileHandle.standardError.write(Data("Interop setup SDK/auth operation failed\n".utf8))
            exit(1)
        }
    }
}
