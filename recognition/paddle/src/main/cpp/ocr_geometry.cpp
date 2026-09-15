#include "ocr_geometry.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace fpink {
namespace {
float cross(Point o, Point a, Point b) {
    return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
}

std::vector<Point> hull(std::vector<Point> points) {
    std::sort(points.begin(), points.end(), [](Point a, Point b) {
        return a.x < b.x || (a.x == b.x && a.y < b.y);
    });
    points.erase(std::unique(points.begin(), points.end(), [](Point a, Point b) {
        return a.x == b.x && a.y == b.y;
    }), points.end());
    if (points.size() < 3) return points;
    std::vector<Point> result(points.size() * 2);
    size_t count = 0;
    for (Point p : points) {
        while (count >= 2 && cross(result[count - 2], result[count - 1], p) <= 0) --count;
        result[count++] = p;
    }
    const size_t lower = count + 1;
    for (size_t i = points.size() - 1; i > 0; --i) {
        const Point p = points[i - 1];
        while (count >= lower && cross(result[count - 2], result[count - 1], p) <= 0) --count;
        result[count++] = p;
    }
    result.resize(count - 1);
    return result;
}

Quad minimum_rectangle(const std::vector<Point>& polygon) {
    float best = std::numeric_limits<float>::max();
    Quad result{};
    for (size_t i = 0; i < polygon.size(); ++i) {
        const Point a = polygon[i], b = polygon[(i + 1) % polygon.size()];
        const float length = std::hypot(b.x - a.x, b.y - a.y);
        if (length < 0.001f) continue;
        const Point u{(b.x - a.x) / length, (b.y - a.y) / length};
        const Point v{-u.y, u.x};
        float lo_u = std::numeric_limits<float>::max(), lo_v = lo_u;
        float hi_u = -std::numeric_limits<float>::max(), hi_v = hi_u;
        for (Point p : polygon) {
            const float pu = p.x * u.x + p.y * u.y, pv = p.x * v.x + p.y * v.y;
            lo_u = std::min(lo_u, pu); hi_u = std::max(hi_u, pu);
            lo_v = std::min(lo_v, pv); hi_v = std::max(hi_v, pv);
        }
        const float area = (hi_u - lo_u) * (hi_v - lo_v);
        if (area < best) {
            best = area;
            auto point = [u, v](float x, float y) { return Point{x * u.x + y * v.x, x * u.y + y * v.y}; };
            result = {point(lo_u, lo_v), point(hi_u, lo_v), point(hi_u, hi_v), point(lo_u, hi_v)};
        }
    }
    const auto top_left = std::min_element(result.begin(), result.end(), [](Point a, Point b) {
        return a.x + a.y < b.x + b.y;
    });
    std::rotate(result.begin(), top_left, result.end());
    return result;
}

float distance(Point a, Point b) { return std::hypot(a.x - b.x, a.y - b.y); }

Quad expand_rectangle(const Quad& box, float amount) {
    const float width = distance(box[0], box[1]), height = distance(box[0], box[3]);
    if (width < 0.001f || height < 0.001f) return box;
    const Point u{(box[1].x - box[0].x) / width, (box[1].y - box[0].y) / width};
    const Point v{(box[3].x - box[0].x) / height, (box[3].y - box[0].y) / height};
    return {{
        {box[0].x - amount * (u.x + v.x), box[0].y - amount * (u.y + v.y)},
        {box[1].x + amount * (u.x - v.x), box[1].y + amount * (u.y - v.y)},
        {box[2].x + amount * (u.x + v.x), box[2].y + amount * (u.y + v.y)},
        {box[3].x + amount * (-u.x + v.x), box[3].y + amount * (-u.y + v.y)},
    }};
}
}  // namespace

std::pair<int, int> detector_size(int width, int height) {
    if (width <= 0 || height <= 0) throw std::invalid_argument("Invalid image dimensions");
    const float scale = 960.f / static_cast<float>(std::max(width, height));
    return {std::max(32, static_cast<int>(std::round(width * scale / 32)) * 32),
            std::max(32, static_cast<int>(std::round(height * scale / 32)) * 32)};
}

std::vector<Box> boxes_from_map(const float* map, int width, int height) {
    if (width < 1 || height < 1 || static_cast<int64_t>(width) * height > 1024 * 1024)
        throw std::runtime_error("Invalid detector output dimensions");
    const int total = width * height;
    for (int i = 0; i < total; ++i)
        if (!std::isfinite(map[i]) || map[i] < 0 || map[i] > 1.001f)
            throw std::runtime_error("Invalid detector probabilities");
    std::vector<uint8_t> visited(total, 0);
    std::vector<int> pending;
    std::vector<Box> results;
    int candidates = 0;
    for (int start = 0; start < total; ++start) {
        if (visited[start] || map[start] <= 0.3f || !std::isfinite(map[start])) continue;
        if (++candidates > 1000) throw std::invalid_argument("Image contains too many text candidates");
        pending.clear();
        pending.push_back(start);
        visited[start] = 1;
        std::vector<Point> boundary;
        for (size_t head = 0; head < pending.size(); ++head) {
            const int index = pending[head], x = index % width, y = index / width;
            bool edge = false;
            for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                if (!dx && !dy) continue;
                const int xx = x + dx, yy = y + dy;
                if (xx < 0 || xx >= width || yy < 0 || yy >= height) { edge = true; continue; }
                const int next = yy * width + xx;
                if (!std::isfinite(map[next]) || map[next] <= 0.3f) { edge = true; continue; }
                if (!visited[next]) { visited[next] = 1; pending.push_back(next); }
            }
            if (edge) boundary.push_back({static_cast<float>(x), static_cast<float>(y)});
        }
        if (pending.size() < 6 || boundary.size() < 3) continue;
        const auto polygon = hull(std::move(boundary));
        if (polygon.size() < 3) continue;
        const Quad box = minimum_rectangle(polygon);
        const float bw = distance(box[0], box[1]), bh = distance(box[0], box[3]);
        if (std::min(bw, bh) < 3) continue;
        float min_x = box[0].x, max_x = min_x, min_y = box[0].y, max_y = min_y;
        for (Point p : box) {
            min_x = std::min(min_x, p.x); max_x = std::max(max_x, p.x);
            min_y = std::min(min_y, p.y); max_y = std::max(max_y, p.y);
        }
        double sum = 0;
        int count = 0;
        for (int y = std::max(0, static_cast<int>(std::floor(min_y)));
             y <= std::min(height - 1, static_cast<int>(std::ceil(max_y))); ++y) {
            for (int x = std::max(0, static_cast<int>(std::floor(min_x)));
                 x <= std::min(width - 1, static_cast<int>(std::ceil(max_x))); ++x) {
                const Point p{static_cast<float>(x), static_cast<float>(y)};
                bool inside = true;
                for (int i = 0; i < 4; ++i) if (cross(box[i], box[(i + 1) % 4], p) < -0.01f) inside = false;
                if (inside) { sum += map[y * width + x]; ++count; }
            }
        }
        const float score = count ? static_cast<float>(sum / count) : 0;
        if (!std::isfinite(score) || score < 0.6f) continue;
        // DB unclip distance = polygon area * 1.5 / perimeter. Expand its minimum rectangle.
        double area = 0, perimeter = 0;
        for (size_t i = 0; i < polygon.size(); ++i) {
            const Point a = polygon[i], b = polygon[(i + 1) % polygon.size()];
            area += a.x * b.y - a.y * b.x;
            perimeter += distance(a, b);
        }
        const float expansion = static_cast<float>(std::abs(area) * 0.5 * 1.5 / perimeter);
        results.push_back({expand_rectangle(box, expansion), score});
        if (results.size() > 256) throw std::invalid_argument("Image contains more than 256 text lines");
    }
    std::stable_sort(results.begin(), results.end(), [](const Box& a, const Box& b) {
        if (a.points[0].y != b.points[0].y) return a.points[0].y < b.points[0].y;
        return a.points[0].x < b.points[0].x;
    });
    return results;
}

Quad normalized(const Quad& box, int width, int height) {
    Quad result = box;
    for (auto& point : result) {
        point.x = std::clamp(point.x / width, 0.f, 1.f);
        point.y = std::clamp(point.y / height, 0.f, 1.f);
    }
    return result;
}

std::array<float, 3> sample_bgr(const std::vector<int32_t>& pixels, int width, int height, float x, float y) {
    x = std::clamp(x, 0.f, static_cast<float>(width - 1));
    y = std::clamp(y, 0.f, static_cast<float>(height - 1));
    const int x0 = static_cast<int>(x), y0 = static_cast<int>(y);
    const int x1 = std::min(x0 + 1, width - 1), y1 = std::min(y0 + 1, height - 1);
    const float fx = x - x0, fy = y - y0;
    std::array<float, 3> result{};
    for (int c = 0; c < 3; ++c) {
        auto value = [&](int xx, int yy) {
            const uint32_t argb = static_cast<uint32_t>(pixels[yy * width + xx]);
            const float alpha = (argb >> 24) / 255.f;
            return ((argb >> (c * 8)) & 255) * alpha + 255.f * (1.f - alpha);
        };
        result[c] = (value(x0, y0) * (1 - fx) + value(x1, y0) * fx) * (1 - fy) +
                    (value(x0, y1) * (1 - fx) + value(x1, y1) * fx) * fy;
    }
    return result;
}

std::pair<std::string, float> decode_ctc(const float* probabilities, int steps, int classes,
                                        const std::vector<std::string>& dictionary) {
    if (steps < 1 || steps > 1024 || classes != static_cast<int>(dictionary.size()) + 2)
        throw std::runtime_error("Recognizer output does not match the pinned v5 dictionary");
    std::string text;
    double confidence = 0;
    int previous = -1, emitted = 0;
    for (int step = 0; step < steps; ++step) {
        const float* row = probabilities + static_cast<size_t>(step) * classes;
        const int token = static_cast<int>(std::max_element(row, row + classes) - row);
        const float probability = row[token];
        if (!std::isfinite(probability) || probability < 0 || probability > 1.001f)
            throw std::runtime_error("Invalid recognizer probabilities");
        if (token != 0 && token != previous) {
            text += token == classes - 1 ? " " : dictionary.at(token - 1);
            confidence += probability;
            ++emitted;
        }
        previous = token;
    }
    return {text, emitted ? static_cast<float>(confidence / emitted) : 0.f};
}
}  // namespace fpink
