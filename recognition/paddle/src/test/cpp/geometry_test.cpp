#include "ocr_geometry.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>

int main() {
    using namespace fpink;
    assert(detector_size(1200, 600) == std::make_pair(960, 480));
    assert(detector_size(600, 1200) == std::make_pair(480, 960));
    assert(detector_size(8192, 16) == std::make_pair(960, 32));
    std::vector<float> blank(200 * 100, 0.f);
    assert(boxes_from_map(blank.data(), 200, 100).empty());
    for (int y = 30; y <= 45; ++y)
        for (int x = 20; x <= 120; ++x) blank[y * 200 + x] = 0.9f;
    auto boxes = boxes_from_map(blank.data(), 200, 100);
    assert(boxes.size() == 1);
    const auto quad = normalized(boxes[0].points, 200, 100);
    assert(quad[0].x > 0.f && quad[0].x < 0.1f);
    assert(quad[0].y > 0.1f && quad[0].y < 0.3f);
    assert(quad[2].x > 0.6f && quad[2].x < 0.8f);
    assert(quad[2].y > 0.45f && quad[2].y < 0.7f);

    const std::vector<std::string> dictionary{"A", "B"};
    // Blank, repeated A, blank, A, B, space. Blank resets CTC repetition.
    const int tokens[] = {0, 1, 1, 0, 1, 2, 3};
    std::vector<float> probabilities(7 * 4, 0.f);
    for (int i = 0; i < 7; ++i) probabilities[i * 4 + tokens[i]] = 0.9f;
    const auto decoded = decode_ctc(probabilities.data(), 7, 4, dictionary);
    assert(decoded.first == "AAB ");
    assert(std::abs(decoded.second - 0.9f) < 0.0001f);
    bool rejected = false;
    try { decode_ctc(probabilities.data(), 7, 3, dictionary); }
    catch (const std::runtime_error&) { rejected = true; }
    assert(rejected);
    rejected = false;
    blank[0] = std::numeric_limits<float>::quiet_NaN();
    try { boxes_from_map(blank.data(), 200, 100); }
    catch (const std::runtime_error&) { rejected = true; }
    assert(rejected);

    // A slanted text component must retain its orientation, not become a full-page rectangle.
    std::vector<float> slanted(200 * 100, 0.f);
    for (int x = 25; x < 150; ++x)
        for (int y = 15 + x / 5; y < 29 + x / 5; ++y) slanted[y * 200 + x] = 0.95f;
    const auto rotated = boxes_from_map(slanted.data(), 200, 100);
    assert(rotated.size() == 1);
    assert(rotated[0].points[1].y > rotated[0].points[0].y + 10);
    assert(rotated[0].points[1].x > rotated[0].points[0].x + 100);

    std::vector<int32_t> argb(4, static_cast<int32_t>(0xff112233));
    const auto color = sample_bgr(argb, 2, 2, 0.5f, 0.5f);
    assert(color[0] == 0x33 && color[1] == 0x22 && color[2] == 0x11);
    argb.assign(4, 0x00112233);
    const auto transparent = sample_bgr(argb, 2, 2, 0, 0);
    assert(transparent[0] == 255 && transparent[1] == 255 && transparent[2] == 255);
    std::cout << "Paddle geometry, coordinates, BGR conversion and CTC tests passed\n";
}
