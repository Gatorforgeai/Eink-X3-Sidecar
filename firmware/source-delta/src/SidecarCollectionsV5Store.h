#pragma once

#include "SidecarCollectionsV5.h"

#include <cstddef>
#include <cstdint>

namespace sidecar::v5 {

struct CapacityAdmission {
  uint64_t total = 0;
  uint64_t used = 0;
  uint64_t free = 0;
  uint64_t reserve = 0;
  uint64_t required = 0;
  bool measured = false;
  bool admitted = false;
};

bool evaluateCapacity(uint64_t total, uint64_t used, uint64_t candidateBytes, CapacityAdmission& result);

class Store {
 public:
  static constexpr const char* kDirectory = "/.crosspoint/sidecar_v5";
  static constexpr uint64_t kMetadataAllowance = 4096;

  bool loadNewestRoot(RootIndex& value) const;
  bool loadCollectionManifest(uint32_t collectionId, uint32_t expectedGeneration,
                              CollectionManifest& value) const;
  bool loadSelection(Selection& value) const;
  bool loadNewestSession(SessionJournal& value) const;

  bool measureAdmission(uint64_t candidateBytes, CapacityAdmission& result) const;
  bool createSession(SessionJournal value, uint64_t candidateBytes, CapacityAdmission& admission);
  bool checkpointSession(SessionJournal value);
  bool abortSession(uint64_t sessionId);
  bool writeManifestVerified(const CollectionManifest& value);
  bool publishRootVerified(RootIndex value);
  bool writeSelectionVerified(const Selection& value);
  bool clearSessionRecords();

  static bool rootPath(uint8_t bank, char* output, size_t capacity);
  static bool manifestPath(uint32_t collectionId, uint8_t bank, char* output, size_t capacity);
  static bool pagePath(uint32_t collectionId, uint8_t bank, uint8_t page, char* output, size_t capacity);
  static bool sessionPath(uint8_t bank, char* output, size_t capacity);

 private:
  bool readRecord(const char* path, uint8_t* output, size_t capacity, size_t& length) const;
  bool writeVerifiedSession(const SessionJournal& value);
};

}  // namespace sidecar::v5
