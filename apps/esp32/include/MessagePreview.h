#pragma once

#include <cstddef>
#include <string>

namespace mesh {
// Input has already passed the bundle codec's UTF-8 validation.
inline std::string messagePreview(const std::string& text, size_t limit = 40) {
    std::string result;
    size_t characters = 0;
    for (size_t at = 0; at < text.size();) {
        if (characters == limit) { result += "..."; break; }
        const unsigned char first = text[at];
        const size_t width = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
        if (width > text.size() - at) break;
        if (first < 0x20 || first == 0x7f) result += ' ';
        else result.append(text, at, width);
        at += width;
        ++characters;
    }
    return result;
}
} // namespace mesh
