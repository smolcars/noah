import Foundation
import NitroModules

extension NoahTools {
    // Shared URLSession for all network requests
    internal static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForResource = 60.0
        config.waitsForConnectivity = false
        config.allowsCellularAccess = true
        config.allowsExpensiveNetworkAccess = true
        config.allowsConstrainedNetworkAccess = true
        return URLSession(configuration: config)
    }()

    internal func performNativePost(
        url: String, body: String, headers: [String: String], timeoutSeconds: Double
    )
        throws -> Promise<HttpResponse>
    {
        return Promise.async {
            guard let requestUrl = URL(string: url) else {
                throw NSError(
                    domain: "NoahTools",
                    code: 100,
                    userInfo: [NSLocalizedDescriptionKey: "Invalid URL: \(url)"]
                )
            }

            var request = URLRequest(url: requestUrl)
            request.httpMethod = "POST"
            request.httpBody = body.data(using: .utf8)
            request.timeoutInterval = timeoutSeconds

            // Set headers
            for (key, value) in headers {
                request.setValue(value, forHTTPHeaderField: key)
            }

            // Use async/await for the network request with the shared session
            do {
                let (data, response) = try await NoahTools.session.data(for: request)

                guard let httpResponse = response as? HTTPURLResponse else {
                    throw NSError(
                        domain: "NoahTools",
                        code: 102,
                        userInfo: [NSLocalizedDescriptionKey: "No HTTP response received"]
                    )
                }

                let responseBody = String(data: data, encoding: .utf8) ?? ""

                // Convert headers to dictionary, handling both single values and arrays
                var responseHeaders: [String: String] = [:]
                for (key, value) in httpResponse.allHeaderFields {
                    guard let keyString = key as? String else { continue }
                    if let valueString = value as? String {
                        responseHeaders[keyString] = valueString
                    } else if let valueArray = value as? [String] {
                        responseHeaders[keyString] = valueArray.joined(separator: ", ")
                    }
                }

                return HttpResponse(
                    status: Double(httpResponse.statusCode),
                    body: responseBody,
                    headers: responseHeaders
                )
            } catch {
                // Handle timeout or other errors
                if (error as NSError).code == NSURLErrorTimedOut {
                    throw NSError(
                        domain: "NoahTools",
                        code: 101,
                        userInfo: [
                            NSLocalizedDescriptionKey:
                                "Request timed out after \(timeoutSeconds) seconds"
                        ]
                    )
                }
                throw error
            }
        }
    }

    internal func performNativeGet(url: String, headers: [String: String], timeoutSeconds: Double)
        throws
        -> Promise<HttpResponse>
    {
        return Promise.async {
            guard let requestUrl = URL(string: url) else {
                throw NSError(
                    domain: "NoahTools",
                    code: 100,
                    userInfo: [NSLocalizedDescriptionKey: "Invalid URL: \(url)"]
                )
            }

            var request = URLRequest(url: requestUrl)
            request.httpMethod = "GET"
            request.timeoutInterval = timeoutSeconds

            // Set headers
            for (key, value) in headers {
                request.setValue(value, forHTTPHeaderField: key)
            }

            // Use async/await for the network request with the shared session
            do {
                let (data, response) = try await NoahTools.session.data(for: request)

                guard let httpResponse = response as? HTTPURLResponse else {
                    throw NSError(
                        domain: "NoahTools",
                        code: 102,
                        userInfo: [NSLocalizedDescriptionKey: "No HTTP response received"]
                    )
                }

                let responseBody = String(data: data, encoding: .utf8) ?? ""

                // Convert headers to dictionary, handling both single values and arrays
                var responseHeaders: [String: String] = [:]
                for (key, value) in httpResponse.allHeaderFields {
                    guard let keyString = key as? String else { continue }
                    if let valueString = value as? String {
                        responseHeaders[keyString] = valueString
                    } else if let valueArray = value as? [String] {
                        responseHeaders[keyString] = valueArray.joined(separator: ", ")
                    }
                }

                return HttpResponse(
                    status: Double(httpResponse.statusCode),
                    body: responseBody,
                    headers: responseHeaders
                )
            } catch {
                // Handle timeout or other errors
                if (error as NSError).code == NSURLErrorTimedOut {
                    throw NSError(
                        domain: "NoahTools",
                        code: 101,
                        userInfo: [
                            NSLocalizedDescriptionKey:
                                "Request timed out after \(timeoutSeconds) seconds"
                        ]
                    )
                }
                throw error
            }
        }
    }

    internal func performUploadFile(
        url: String, path: String, headers: [String: String], timeoutSeconds: Double
    ) throws -> Promise<Void> {
        return Promise.async {
            guard let requestURL = URL(string: url) else {
                throw NSError(
                    domain: "NoahTools", code: 100,
                    userInfo: [NSLocalizedDescriptionKey: "Invalid upload URL"])
            }
            let fileURL = URL(fileURLWithPath: path)
            guard FileManager.default.fileExists(atPath: fileURL.path) else {
                throw NSError(
                    domain: "NoahTools", code: 103,
                    userInfo: [NSLocalizedDescriptionKey: "Upload file does not exist"])
            }

            var request = URLRequest(url: requestURL)
            request.httpMethod = "PUT"
            request.timeoutInterval = timeoutSeconds
            for (key, value) in headers {
                request.setValue(value, forHTTPHeaderField: key)
            }
            let (_, response) = try await NoahTools.session.upload(for: request, fromFile: fileURL)
            guard let httpResponse = response as? HTTPURLResponse,
                (200..<300).contains(httpResponse.statusCode)
            else {
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                throw NSError(
                    domain: "NoahTools", code: 104,
                    userInfo: [NSLocalizedDescriptionKey: "Backup upload failed with status \(status)"])
            }
        }
    }

    internal func performDownloadFile(
        url: String, path: String, headers: [String: String], timeoutSeconds: Double
    ) throws -> Promise<Void> {
        return Promise.async {
            guard let requestURL = URL(string: url) else {
                throw NSError(
                    domain: "NoahTools", code: 100,
                    userInfo: [NSLocalizedDescriptionKey: "Invalid download URL"])
            }
            let destinationURL = URL(fileURLWithPath: path)
            guard !FileManager.default.fileExists(atPath: destinationURL.path) else {
                throw NSError(
                    domain: "NoahTools", code: 105,
                    userInfo: [NSLocalizedDescriptionKey: "Download destination already exists"])
            }

            var request = URLRequest(url: requestURL)
            request.httpMethod = "GET"
            request.timeoutInterval = timeoutSeconds
            for (key, value) in headers {
                request.setValue(value, forHTTPHeaderField: key)
            }
            let (temporaryURL, response) = try await NoahTools.session.download(for: request)
            guard let httpResponse = response as? HTTPURLResponse,
                (200..<300).contains(httpResponse.statusCode)
            else {
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                throw NSError(
                    domain: "NoahTools", code: 106,
                    userInfo: [NSLocalizedDescriptionKey: "Backup download failed with status \(status)"])
            }
            try FileManager.default.moveItem(at: temporaryURL, to: destinationURL)
        }
    }
}
