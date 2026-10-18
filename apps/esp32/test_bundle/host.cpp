// Intentionally one translation unit to exercise the private BIP340 verifier
// against official vectors without adding a test-only production API.
#include "../src/MeshBundle.cpp"
#include <fstream>
#include <iostream>
#include <iterator>

static std::vector<uint8_t> unhex(const std::string& s) {
    std::vector<uint8_t> result;
    for (size_t i = 0; i + 1 < s.size(); i += 2)
        result.push_back(static_cast<uint8_t>(std::stoul(s.substr(i, 2), nullptr, 16)));
    return result;
}
int main(int argc, char** argv) {
    if (argc == 5 && std::string(argv[1]) == "bip340") {
        auto pub = unhex(argv[2]), sig = unhex(argv[3]), msg = unhex(argv[4]);
        if (msg.size() != 32) return 2;
        bool valid = mesh::verifySchnorr({pub.data(), pub.size()}, {sig.data(), sig.size()}, msg.data());
        std::cout << (valid ? "valid" : "invalid") << '\n';
        return valid ? 0 : 1;
    }
    if (argc != 2 && argc != 4) return 2;
    std::ifstream input(argv[1], std::ios::binary);
    if (!input) return 2;
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)), {});
    mesh::BundleInfo info;
    info.id = "stale";
    std::string error = "stale";
    if (!mesh::validateBundle(bytes.data(), bytes.size(), info, error)) {
        if (!info.id.empty() || !info.text.empty() || error.empty()) return 3;
        std::cerr << error << '\n'; return 1;
    }
    if (!error.empty()) return 3;
    if (argc == 4) {
        std::vector<uint8_t> out(1, 42);
        if (!mesh::forwardBundle(bytes.data(), bytes.size(), std::stoull(argv[2]), out, error)) {
            if (!out.empty() || error.empty()) return 3;
            std::cerr << error << '\n'; return 1;
        }
        if (!error.empty()) return 3;
        std::ofstream output(argv[3], std::ios::binary);
        output.write(reinterpret_cast<const char*>(out.data()), static_cast<std::streamsize>(out.size()));
        if (!output) return 2;
    }
    std::cout << info.id << ' ' << info.age << ' ' << info.lifetime << ' ' << info.hop << ' ' << info.hopLimit
              << ' ' << mesh::hex({reinterpret_cast<const uint8_t*>(info.text.data()), info.text.size()}) << '\n';
    return 0;
}
