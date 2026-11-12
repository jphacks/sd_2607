import Foundation
import Network
import XCTest
@testable import MosimoCore

final class LocalLinkTests: XCTestCase {
    private final class FakeRadio: Link {
        let name = "radio"
        var on = false
        func start(node: String, listener: LinkListener) { on = true }
        func stop() { on = false }
        func send(_ peer: String, _ message: [UInt8]) -> Bool { false }
        func queued(_ peer: String) -> Int { 0 }
        func disconnect(_ peer: String) {}
        func synced(_ peer: String) {}
    }

    /// Stands in for Exchange: says its node (a CBOR text string) on connect and reports the peer's as HELLO.
    private final class Node: LinkListener {
        let id: String
        var hellos = [String]()
        init(_ id: String) { self.id = id }
        func onConnected(_ link: Link, _ peer: String, node: String?) { _ = link.send(peer, [0x70] + Array(id.utf8)) }
        func onMessage(_ link: Link, _ peer: String, _ message: [UInt8]) {
            hellos.append(String(decoding: message.dropFirst(), as: UTF8.self)); link.hello(peer)
        }
        func onDrained(_ link: Link, _ peer: String) {}
        func onDisconnected(_ link: Link, _ peer: String) {}
    }

    private func freePort() -> UInt16 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        defer { Darwin.close(fd) }
        var a = sockaddr_in(sin_len: UInt8(MemoryLayout<sockaddr_in>.size), sin_family: sa_family_t(AF_INET), sin_port: 0,
                            sin_addr: in_addr(s_addr: inet_addr("127.0.0.1")), sin_zero: (0, 0, 0, 0, 0, 0, 0, 0))
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutablePointer(to: &a) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { _ = Darwin.bind(fd, $0, len); _ = getsockname(fd, $0, &len) } }
        return UInt16(bigEndian: a.sin_port)
    }

    /// Link callbacks hop to the main queue, so spin it while waiting.
    private func waitFor(_ seconds: Double, _ ok: () -> Bool, file: StaticString = #filePath, line: UInt = #line) {
        let until = Date() + seconds
        while !ok() && Date() < until { RunLoop.main.run(until: Date() + 0.05) }
        XCTAssertTrue(ok(), file: file, line: line)
    }

    private func spin(_ seconds: Double) {
        let until = Date() + seconds
        while Date() < until { RunLoop.main.run(until: Date() + 0.05) }
    }

    func testOneDeviceRunsOneSetOfRadiosAndAFollowerTakesOver() {
        let ports = [freePort()]
        let bleA = FakeRadio(), lanA = FakeRadio(), bleB = FakeRadio(), lanB = FakeRadio()
        let a = LocalLink(radios: [bleA, lanA], ports: ports), b = LocalLink(radios: [bleB, lanB], ports: ports)
        let nodeA = Node("00000000000000aa"), nodeB = Node("00000000000000bb")
        a.start(node: nodeA.id, listener: nodeA)
        waitFor(5) { bleA.on && lanA.on }
        b.start(node: nodeB.id, listener: nodeB)
        waitFor(5) { nodeB.hellos == ["00000000000000aa"] }
        spin(0.5)
        XCTAssertFalse(bleB.on || lanB.on)

        a.stop()
        waitFor(1) { !bleA.on && !lanA.on }
        waitFor(10) { bleB.on && lanB.on } // b binds the freed port
        b.stop()
        waitFor(1) { !bleB.on && !lanB.on }

        // Started together: both find the port free, one bind loses and follows the winner.
        a.start(node: nodeA.id, listener: nodeA); b.start(node: nodeB.id, listener: nodeB)
        waitFor(5) { bleA.on != bleB.on }
        spin(1)
        XCTAssertNotEqual(bleA.on, bleB.on)
        a.stop(); b.stop()
    }

    func testAPortUsedByAnotherAppFallsBackToTheNext() throws {
        let params = NWParameters.tcp
        let foreignPort = freePort()
        params.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: foreignPort)!)
        let foreign = try NWListener(using: params) // hangs up without HELLO
        foreign.newConnectionHandler = { c in c.start(queue: .global()); c.cancel() }
        foreign.start(queue: .global())
        let ports = [foreignPort, freePort()]
        let radioA = FakeRadio(), radioB = FakeRadio(), radioC = FakeRadio()
        let nodeA = Node("00000000000000aa"), nodeB = Node("00000000000000bb"), nodeC = Node("00000000000000cc")
        let a = LocalLink(radios: [radioA], ports: ports)
        a.start(node: nodeA.id, listener: nodeA)
        waitFor(5) { radioA.on } // skipped the foreign port, holds the fallback

        let b = LocalLink(radios: [radioB], ports: ports)
        b.start(node: nodeB.id, listener: nodeB)
        waitFor(5) { nodeB.hellos == ["00000000000000aa"] }
        spin(0.5)
        XCTAssertFalse(radioB.on) // found a on the fallback

        let c = LocalLink(radios: [radioC], ports: [foreignPort])
        c.start(node: nodeC.id, listener: nodeC)
        waitFor(5) { radioC.on } // every port foreign: never leave the device without radios
        c.stop()

        // The other app frees the first port before a's recheck: a newcomer joins a instead of holding it beside a.
        foreign.cancel()
        spin(0.5)
        let radioD = FakeRadio(), nodeD = Node("00000000000000dd")
        let d = LocalLink(radios: [radioD], ports: ports)
        d.start(node: nodeD.id, listener: nodeD)
        waitFor(5) { nodeD.hellos == ["00000000000000aa"] }
        spin(0.5)
        XCTAssertFalse(radioD.on)
        a.stop(); b.stop(); d.stop()
    }
}
