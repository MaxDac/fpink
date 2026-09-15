#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace fpink {

struct Point { float x; float y; };
using Quad = std::array<Point, 4>;
struct Box { Quad points; float score; };

std::pair<int, int> detector_size(int width, int height);
std::vector<Box> boxes_from_map(const float* map, int width, int height);
Quad normalized(const Quad& box, int width, int height);
std::array<float, 3> sample_bgr(const std::vector<int32_t>& pixels, int width, int height, float x, float y);
std::pair<std::string, float> decode_ctc(const float* probabilities, int steps, int classes,
                                        const std::vector<std::string>& dictionary);

}  // namespace fpink
