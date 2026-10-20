import Foundation
import Network

/// Loopback only. Tests own every connection and cancel them before returning.
final class LocalEsploraServer: @unchecked Sendable {
    enum Reply { case silent, drip, oversized }
    private let listener: NWListener
    private let queue = DispatchQueue(label: "test.esplora-server")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    let port: UInt16

    init(_ reply: Reply) throws {
        listener = try NWListener(using: .tcp, on: .any)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in
            switch state { case .ready, .failed: ready.signal(); default: break }
        }
        listener.start(queue: queue)
        precondition(ready.wait(timeout: .now() + 5) == .success)
        port = listener.port!.rawValue
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            lock.withLock { connections.append(connection) }
            connection.start(queue: queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] _, _, _, _ in
                guard let self else { return }
                switch reply {
                case .silent: break
                case .oversized:
                    connection.send(content: Data(("HTTP/1.1 200 OK\r\nContent-Length: 2097152\r\n\r\n" + String(repeating: "x", count: 2097152)).utf8), completion: .contentProcessed { _ in })
                case .drip:
                    connection.send(content: Data("HTTP/1.1 200 OK\r\nContent-Length: 64\r\n\r\n".utf8), completion: .contentProcessed { _ in })
                    drip(connection, remaining: 64)
                }
            }
        }
    }
    private func drip(_ connection: NWConnection, remaining: Int) {
        guard remaining > 0 else { return }
        connection.send(content: Data("0".utf8), completion: .contentProcessed { [weak self] error in
            guard error == nil, let self else { return }
            queue.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.drip(connection, remaining: remaining - 1) }
        })
    }
    var url: String { "http://127.0.0.1:\(port)" }
    func stop() {
        listener.cancel()
        lock.withLock { connections.forEach { $0.cancel() }; connections.removeAll() }
    }
    deinit { stop() }
}
