#include "MessagePreview.h"
#include <cassert>
#include <iostream>

int main() {
    using mesh::messagePreview;
    assert(messagePreview("").empty());
    assert(messagePreview("hello") == "hello");
    assert(messagePreview("\nhello\r\tworld\x1b[31m") == " hello  world [31m");
    assert(messagePreview(std::string("a\0b", 3)) == "a b");
    assert(messagePreview(std::string(40, 'a')) == std::string(40, 'a'));
    assert(messagePreview(std::string(41, 'a')) == std::string(40, 'a') + "...");
    assert(messagePreview(u8"避難所へ移動してください", 3) == u8"避難所...");
    assert(messagePreview(u8"😀日本語", 2) == u8"😀日...");
    assert(messagePreview(std::string(39, 'a') + u8"日語") == std::string(39, 'a') + u8"日...");
    assert(messagePreview("abc", 0) == "...");
    std::cout << "PASS: 10 UTF-8 preview checks\n";
}
