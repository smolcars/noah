import Foundation

/// Streams a download to a file and stops as soon as more than `limit`
/// bytes arrive, so a response can't fill the disk before its size is
/// checked. Ephemeral session: nothing is cached.
enum BoundedDownload {
    struct TooLarge: Error, Equatable {}

    static func fetch(_ request: URLRequest, to destination: URL, limit: Int64) async throws {
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let handle = try FileHandle(forWritingTo: destination)
        defer { try? handle.close() }
        let receiver = Receiver(handle: handle, limit: limit)
        let session = URLSession(configuration: .ephemeral, delegate: receiver, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let task = session.dataTask(with: request)
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                receiver.start(task, continuation: continuation)
            }
        } onCancel: {
            task.cancel()
        }
    }

    /// Delegate callbacks arrive on the session's serial queue; `lock`
    /// covers `start`, which runs on the caller's. A task cancelled before
    /// `start` can complete first, so the outcome is kept for `start`.
    private final class Receiver: NSObject, URLSessionDataDelegate, @unchecked Sendable {
        private let handle: FileHandle
        private let limit: Int64
        private let lock = NSLock()
        private var received: Int64 = 0
        private var failure: Error?
        private var outcome: Result<Void, Error>?
        private var continuation: CheckedContinuation<Void, Error>?

        init(handle: FileHandle, limit: Int64) {
            self.handle = handle
            self.limit = limit
        }

        func start(_ task: URLSessionDataTask, continuation: CheckedContinuation<Void, Error>) {
            let finished: Result<Void, Error>? = lock.withLock {
                if outcome == nil { self.continuation = continuation }
                return outcome
            }
            if let finished {
                continuation.resume(with: finished)
            } else {
                task.resume()
            }
        }

        func urlSession(
            _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
            completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
        ) {
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                fail(URLError(.badServerResponse), completionHandler)
            } else if response.expectedContentLength > limit {
                fail(TooLarge(), completionHandler)
            } else {
                completionHandler(.allow)
            }
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            guard lock.withLock({ failure == nil }) else { return }
            received += Int64(data.count)
            do {
                guard received <= limit else { throw TooLarge() }
                try handle.write(contentsOf: data)
            } catch {
                lock.withLock { failure = error }
                dataTask.cancel()
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            let (continuation, result): (CheckedContinuation<Void, Error>?, Result<Void, Error>) = lock.withLock {
                let result: Result<Void, Error> = (failure ?? error).map { .failure($0) } ?? .success(())
                outcome = result
                defer { self.continuation = nil }
                return (self.continuation, result)
            }
            continuation?.resume(with: result)
        }

        private func fail(_ error: Error, _ completionHandler: (URLSession.ResponseDisposition) -> Void) {
            lock.withLock { failure = error }
            completionHandler(.cancel)
        }
    }
}
