import Foundation

/// One Nostr relay WebSocket; every callback arrives on the main actor.
final class RelaySocket: NSObject, URLSessionWebSocketDelegate {
    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    private let onOpen: @MainActor () -> Void
    private let onMessage: @MainActor (String) -> Void
    private let onFailure: @MainActor () -> Void
    private var finished = false

    init(url: URL, onOpen: @escaping @MainActor () -> Void, onMessage: @escaping @MainActor (String) -> Void, onFailure: @escaping @MainActor () -> Void) {
        self.onOpen = onOpen; self.onMessage = onMessage; self.onFailure = onFailure
        super.init()
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        let t = session.webSocketTask(with: url)
        t.maximumMessageSize = 262144
        task = t
        t.resume()
    }

    func send(_ text: String) -> Bool {
        guard let task, !finished else { return false }
        task.send(.string(text)) { [weak self] error in if error != nil { self?.fail() } }
        return true
    }

    func close() {
        finished = true
        task?.cancel(with: .normalClosure, reason: nil)
        session.invalidateAndCancel()
    }

    private func fail() {
        guard !finished else { return }
        finished = true
        task?.cancel()
        session.invalidateAndCancel()
        let f = onFailure
        Task { @MainActor in f() }
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self, !self.finished else { return }
            switch result {
            case .success(.string(let text)):
                let m = self.onMessage
                Task { @MainActor in m(text) }
                self.receive()
            case .success: self.fail() // binary frames are not part of NIP-01
            case .failure: self.fail()
            }
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        let o = onOpen
        Task { @MainActor in o() }
        receive()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) { fail() }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) { fail() }
}
