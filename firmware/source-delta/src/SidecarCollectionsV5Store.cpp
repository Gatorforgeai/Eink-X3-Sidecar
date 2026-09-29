#include "SidecarCollectionsV5Store.h"

#include <HalStorage.h>
#include <cstring>

namespace sidecar::v5 {
namespace {
constexpr const char* kSelectionPath = "/.crosspoint/sidecar_v5/selection.bin";
}  // namespace

bool Store::readRecord(const char* path, uint8_t* out, size_t cap, size_t& length) const {
  length = 0; if (!path || !out || !cap) return false;
  HalFile file; if (!Storage.openFileForRead("SCV5", path, file)) return false;
  const size_t size = file.size();
  if (!size || size > cap || file.read(out, size) != static_cast<int>(size)) { file.close(); return false; }
  file.close(); length = size; return true;
}

bool Store::loadNewestRoot(RootIndex& value) const {
  RootIndex records[2]{}; bool valid[2] = {};
  for (uint8_t bank = 0; bank < 2; ++bank) {
    char path[64]; uint8_t bytes[kRootMaxSize]; size_t length = 0;
    valid[bank] = rootPath(bank, path, sizeof(path)) && readRecord(path, bytes, sizeof(bytes), length) &&
                  decodeRoot(bytes, length, records[bank]) && records[bank].bank == bank;
  }
  if (!valid[0] && !valid[1]) return false;
  value = valid[0] && (!valid[1] || generationIsNewerOrEqual(records[0].generation, records[1].generation))
              ? records[0] : records[1];
  return true;
}

bool Store::loadCollectionManifest(uint32_t id, uint32_t expected, CollectionManifest& value) const {
  if (!id || !expected) return false;
  CollectionManifest records[2]{}; bool valid[2] = {};
  for (uint8_t bank = 0; bank < 2; ++bank) {
    char path[72]; uint8_t bytes[kManifestSize]; size_t length = 0;
    valid[bank] = manifestPath(id, bank, path, sizeof(path)) && readRecord(path, bytes, sizeof(bytes), length) &&
                  decodeManifest(bytes, length, records[bank]) && records[bank].bank == bank &&
                  records[bank].collectionId == id && records[bank].generation == expected;
  }
  if (!valid[0] && !valid[1]) return false;
  value = valid[0] ? records[0] : records[1]; return true;
}

bool Store::loadSelection(Selection& value) const {
  uint8_t bytes[kSelectionSize]; size_t length = 0;
  return readRecord(kSelectionPath, bytes, sizeof(bytes), length) && decodeSelection(bytes, length, value);
}

bool Store::loadNewestSession(SessionJournal& value) const {
  SessionJournal records[2]{}; bool valid[2] = {};
  for (uint8_t bank = 0; bank < 2; ++bank) {
    char path[64]; uint8_t bytes[kSessionSize]; size_t length = 0;
    valid[bank] = sessionPath(bank, path, sizeof(path)) && readRecord(path, bytes, sizeof(bytes), length) &&
                  decodeSession(bytes, length, records[bank]) && records[bank].journalBank == bank;
  }
  if (!valid[0] && !valid[1]) return false;
  value = valid[0] && (!valid[1] || generationIsNewerOrEqual(records[0].journalSequence, records[1].journalSequence))
              ? records[0] : records[1];
  return true;
}

bool Store::measureAdmission(uint64_t candidateBytes, CapacityAdmission& out) const {
  if (!Storage.ready()) { out = {}; return false; }
  return evaluateCapacity(Storage.totalBytes(), Storage.usedBytes(), candidateBytes, out);
}

bool Store::writeVerifiedSession(const SessionJournal& value) {
  uint8_t bytes[kSessionSize]; if (!encodeSession(value, bytes, sizeof(bytes))) return false;
  if (!Storage.ensureDirectoryExists(kDirectory)) return false;
  char path[64]; if (!sessionPath(value.journalBank, path, sizeof(path))) return false;
  if (Storage.exists(path) && !Storage.remove(path)) return false;
  HalFile file; if (!Storage.openFileForWrite("SCV5", path, file)) return false;
  const size_t written = file.write(bytes, sizeof(bytes)); file.flush(); file.close();
  if (written != sizeof(bytes)) return false;
  SessionJournal check{}; uint8_t readback[kSessionSize]; size_t length = 0;
  return readRecord(path, readback, sizeof(readback), length) && decodeSession(readback, length, check) &&
         check.sessionId == value.sessionId && check.journalSequence == value.journalSequence &&
         check.journalBank == value.journalBank;
}

bool Store::createSession(SessionJournal value, uint64_t candidateBytes, CapacityAdmission& admission) {
  SessionJournal existing{};
  if (loadNewestSession(existing) || !measureAdmission(candidateBytes, admission) || !admission.admitted) return false;
  value.journalBank = 0; value.journalSequence = 1;
  return writeVerifiedSession(value);
}

bool Store::checkpointSession(SessionJournal value) {
  SessionJournal current{};
  if (!loadNewestSession(current) || current.sessionId != value.sessionId ||
      current.collectionId != value.collectionId || current.baseRootGeneration != value.baseRootGeneration ||
      current.baseCollectionGeneration != value.baseCollectionGeneration ||
      current.candidateCollectionGeneration != value.candidateCollectionGeneration ||
      current.resultingPageCount != value.resultingPageCount || current.candidateBank != value.candidateBank ||
      current.mode != value.mode || current.requiredPageBitmap != value.requiredPageBitmap ||
      (current.state == SessionState::Ready && value.state != SessionState::Ready) ||
      (value.receivedPageBitmap & current.receivedPageBitmap) != current.receivedPageBitmap)
    return false;
  for (uint8_t page = 0; page < kMaxPagesPerCollection; ++page)
    if (current.pageCrc[page] != value.pageCrc[page]) return false;
  value.journalBank = current.journalBank ^ 1u; value.journalSequence = current.journalSequence + 1u;
  if (!value.journalSequence || !writeVerifiedSession(value)) return false;
  // Keep the prior valid bank as rollback evidence. The next checkpoint overwrites it only after
  // the other bank is durable and read-verified.
  return true;
}

bool Store::abortSession(uint64_t sessionId) {
  SessionJournal current{}; if (!sessionId || !loadNewestSession(current) || current.sessionId != sessionId) return false;
  bool published = false;
  // Check both durable root banks. The older valid root is rollback state and must protect every
  // object it references just as strongly as the selected/newest root.
  for (uint8_t bank = 0; bank < 2; ++bank) {
    char rootFile[64]; uint8_t bytes[kRootMaxSize]; size_t length = 0; RootIndex root{};
    if (!rootPath(bank, rootFile, sizeof(rootFile)) ||
        !readRecord(rootFile, bytes, sizeof(bytes), length) || !decodeRoot(bytes, length, root)) continue;
    for (uint8_t i = 0; i < root.entryCount; ++i)
      if (root.entries[i].collectionId == current.collectionId &&
          root.entries[i].expectedGeneration == current.candidateCollectionGeneration) published = true;
  }
  if (!published) {
    bool cleanupOk = true;
    char path[72];
    if (!manifestPath(current.collectionId, current.candidateBank, path, sizeof(path))) return false;
    if (Storage.exists(path) && !Storage.remove(path)) cleanupOk = false;
    for (uint8_t page = 0; page < current.resultingPageCount; ++page) {
      if (!pagePath(current.collectionId, current.candidateBank, page, path, sizeof(path))) return false;
      if (Storage.exists(path) && !Storage.remove(path)) cleanupOk = false;
    }
    // Keep the journal when candidate cleanup fails so a later abort can retry safely.
    if (!cleanupOk) return false;
  }
  bool ok = true;
  for (uint8_t bank = 0; bank < 2; ++bank) {
    char path[64];
    if (sessionPath(bank, path, sizeof(path)) && Storage.exists(path) && !Storage.remove(path)) ok = false;
  }
  return ok;
}

namespace {
bool removeIfPresent(const char* path) {
  return !Storage.exists(path) || Storage.remove(path) || !Storage.exists(path);
}
bool writeRecord(const char* path, const uint8_t* bytes, size_t length) {
  if (!removeIfPresent(path)) return false;
  HalFile file; if (!Storage.openFileForWrite("SCV5", path, file)) return false;
  const size_t n = file.write(bytes, length); file.flush(); file.close();
  if (n != length) return false;
  HalFile check; if (!Storage.openFileForRead("SCV5", path, check) || check.size() != length) {
    if (check) check.close(); return false;
  }
  uint8_t chunk[128]; size_t offset = 0;
  while (offset < length) {
    const size_t wanted = length - offset < sizeof(chunk) ? length - offset : sizeof(chunk);
    if (check.read(chunk, wanted) != static_cast<int>(wanted) ||
        std::memcmp(chunk, bytes + offset, wanted)) { check.close(); return false; }
    offset += wanted;
  }
  check.close(); return true;
}
}

bool Store::writeManifestVerified(const CollectionManifest& value) {
  uint8_t bytes[kManifestSize]; char path[72];
  return Storage.ensureDirectoryExists(kDirectory) && encodeManifest(value, bytes, sizeof(bytes)) &&
         manifestPath(value.collectionId, value.bank, path, sizeof(path)) &&
         writeRecord(path, bytes, sizeof(bytes));
}

bool Store::publishRootVerified(RootIndex value) {
  RootIndex current{}; const bool have = loadNewestRoot(current);
  value.bank = have ? static_cast<uint8_t>(current.bank ^ 1u) : 0;
  uint8_t bytes[kRootMaxSize]; size_t length = 0; char path[64];
  if (!Storage.ensureDirectoryExists(kDirectory) || !encodeRoot(value, bytes, sizeof(bytes), length) ||
      !rootPath(value.bank, path, sizeof(path)) || !writeRecord(path, bytes, length)) return false;
  RootIndex check{}; return loadNewestRoot(check) && check.generation == value.generation &&
                            check.bank == value.bank;
}

bool Store::writeSelectionVerified(const Selection& value) {
  uint8_t bytes[kSelectionSize];
  return Storage.ensureDirectoryExists(kDirectory) && encodeSelection(value, bytes, sizeof(bytes)) &&
         writeRecord(kSelectionPath, bytes, sizeof(bytes));
}

bool Store::clearSessionRecords() {
  bool ok = true;
  for (uint8_t bank = 0; bank < 2; ++bank) {
    char path[64]; if (!sessionPath(bank, path, sizeof(path))) return false;
    if (!removeIfPresent(path)) ok = false;
  }
  return ok;
}

}  // namespace sidecar::v5
