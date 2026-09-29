#include "SidecarCollectionsV5Store.h"

#include <cstdio>

namespace sidecar::v5 {
namespace {
bool formatPath(char* output, size_t capacity, const char* format, uint32_t id, uint8_t bank, int page = -1) {
  if (!output || !capacity || bank > 1) return false;
  const int n = page < 0 ? std::snprintf(output, capacity, format, static_cast<unsigned long>(id), bank)
                         : std::snprintf(output, capacity, format, static_cast<unsigned long>(id), bank, page);
  return n > 0 && static_cast<size_t>(n) < capacity;
}
}  // namespace

bool evaluateCapacity(uint64_t total, uint64_t used, uint64_t candidateBytes, CapacityAdmission& out) {
  out = {}; out.total = total; out.used = used;
  if (!total || !used || used > total || candidateBytes > UINT64_MAX - Store::kMetadataAllowance) return false;
  out.measured = true; out.free = total - used;
  const uint64_t fivePercent = total / 20u + ((total % 20u) != 0u);
  out.reserve = fivePercent > 8u * 1024u * 1024u ? fivePercent : 8u * 1024u * 1024u;
  out.required = candidateBytes + Store::kMetadataAllowance;
  out.admitted = out.free >= out.reserve && out.required <= out.free - out.reserve;
  return true;
}

bool Store::rootPath(uint8_t bank, char* out, size_t cap) {
  return formatPath(out, cap, "/.crosspoint/sidecar_v5/root_%lu.index", bank, bank);
}
bool Store::manifestPath(uint32_t id, uint8_t bank, char* out, size_t cap) {
  return id && formatPath(out, cap, "/.crosspoint/sidecar_v5/c_%08lX_%u.manifest", id, bank);
}
bool Store::pagePath(uint32_t id, uint8_t bank, uint8_t page, char* out, size_t cap) {
  return id && page < kMaxPagesPerCollection &&
         formatPath(out, cap, "/.crosspoint/sidecar_v5/c_%08lX_%u_%u.bin", id, bank, page);
}
bool Store::sessionPath(uint8_t bank, char* out, size_t cap) {
  return formatPath(out, cap, "/.crosspoint/sidecar_v5/stage_%lu.session", bank, bank);
}
}  // namespace sidecar::v5
