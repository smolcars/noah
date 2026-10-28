import Foundation
import Network

/// Loopback only. Tests own every connection and cancel them before returning.
final class LocalEsploraServer: @unchecked Sendable {
    /// `raw`: exactly these bytes, then close the connection or leave it open.
    enum Reply { case silent, drip, oversized, notFound, raw(String, close: Bool) }
    private let listener: NWListener
    private let queue = DispatchQueue(label: "test.esplora-server")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var received: [String] = []
    /// The request line of each connection, in arrival order.
    var requests: [String] { lock.withLock { received } }
    private(set) var port: UInt16 = 0

    init(_ reply: Reply) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in
            switch state { case .ready, .failed: ready.signal(); default: break }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            lock.withLock { connections.append(connection) }
            connection.start(queue: queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] request, _, _, _ in
                guard let self else { return }
                let line = (String(bytes: request ?? Data(), encoding: .utf8) ?? "").components(separatedBy: "\r\n")[0]
                lock.withLock { received.append(line) }
                switch reply {
                case .silent: break
                case .raw(let response, let close):
                    connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in if close { connection.cancel() } })
                case .notFound:
                    let genesis = "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"
                    let response = (String(bytes: request ?? Data(), encoding: .utf8) ?? "").contains("GET /block-height/0 ")
                        ? "HTTP/1.1 200 OK\r\nContent-Length: 64\r\nConnection: close\r\n\r\n\(genesis)"
                        : "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
                case .oversized:
                    connection.send(
                        content: Data(("HTTP/1.1 200 OK\r\nContent-Length: 2097152\r\n\r\n" + String(repeating: "x", count: 2097152)).utf8),
                        completion: .contentProcessed { _ in })
                case .drip:
                    connection.send(content: Data("HTTP/1.1 200 OK\r\nContent-Length: 64\r\n\r\n".utf8), completion: .contentProcessed { _ in })
                    drip(connection, remaining: 64)
                }
            }
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success,
              let boundPort = listener.port, boundPort.rawValue != 0 else { throw URLError(.cannotConnectToHost) }
        port = boundPort.rawValue
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
