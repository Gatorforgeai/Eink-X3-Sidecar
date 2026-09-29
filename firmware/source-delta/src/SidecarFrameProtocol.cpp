#include "SidecarFrameProtocol.h"
#include "SidecarSink.h"
#include <Arduino.h>
#include <Logging.h>

#ifdef SIDECAR_LINK

// DESIGN INTENT:
// DONE-WHEN: v1 frames behave unchanged, v2/v3 accept bounded bundles, and v4 parses bounded
// atomic page-group replacements; every page is verified and completion follows atomic commit.
// INPUTS: every opcode/state, malformed lengths, sequence gaps, CRC mismatch, disconnect, SD error.
// FAILS-WHEN: partial data is published, a bad CRC reaches storage, or a v1 sender stops working.

namespace {
uint32_t crc32Byte(uint32_t crc, uint8_t value) {
    crc ^= value;
    for (uint8_t bit = 0; bit < 8; ++bit) {
        crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
    return crc;
}
}

SidecarFrameProtocol::SidecarFrameProtocol(SidecarSink* sink)
    : sink_(sink),
      frameComplete_(false),
      state_(State::Idle),
      hdrIdx_(0),
      x_(0), y_(0), w_(0), h_(0),
      format_(0),
      pageMode_(false), groupMode_(false), beginHeaderLength_(13), pageIndex_(0), pageCount_(0), activePage_(0),
      bundleNextPage_(0), bundleActivePage_(0), bundlePageCount_(0),
      groupStart_(0), groupCount_(0), groupOffset_(0),
      bundleGroupStart_(0), bundleGroupCount_(0), bundleTransactionActive_(false),
      expectedCrc_(0), runningCrc_(0xFFFFFFFFu),
      dataLength_(0), dataReceived_(0),
      nextSeq_(0), rowY_(0), bufIdx_(0) {
}

void SidecarFrameProtocol::onWrite(const uint8_t* data, uint16_t len) {
    if (data == nullptr || len == 0) { abortTransfer(); return; }
    if (sink_ == nullptr) {
        static bool logged = false;
        if (!logged) {
            LOG_ERR("FRAME", "%s", "frame rejected: sink unavailable");
            logged = true;
        }
        abortTransfer();
        return;
    }

    static uint32_t frameStartTime = 0;
    char logBuf[96];

    uint16_t i = 0;
    while (i < len) {
        uint8_t byte = data[i];

        switch (state_) {
            case State::Idle:
                if (sink_->hasPendingBundle() && (byte == kOpBegin || byte == kOpBeginPage ||
                    byte == kOpBeginGroupPage || byte == kOpClear)) {
                    LOG_ERR("FRAME", "%s", "legacy operation rejected while deferred catalog is pending");
                    abortTransfer();  // The sink preserves its fully verified pending catalog.
                    i = len;
                    break;
                }
                if (bundleTransactionActive_) {
                    const bool matchingContinuation =
                        (groupMode_ && byte == kOpBeginGroupPage) ||
                        (!groupMode_ && byte == kOpBeginPage);
                    if (!matchingContinuation) {
                        LOG_ERR("FRAME", "%s", "operation rejected while bundle is staging");
                        abortTransfer();
                        i = len;
                        break;
                    }
                }
                if (byte == kOpBegin || byte == kOpBeginPage || byte == kOpBeginGroupPage) {
                    const bool pageMode = byte == kOpBeginPage || byte == kOpBeginGroupPage;
                    const bool groupMode = byte == kOpBeginGroupPage;
                    // Only a non-zero page/offset can continue staging. Any new transaction
                    // abandons the previous staging state before its header is parsed.
                    // Wire byte 0 is the opcode. Group offset is hdrBuf_[18], therefore data[19]:
                    // op, x2, y2, w2, h2, format, length4, page, count, active, start, groupCount, offset.
                    const bool transactionStart = groupMode
                        ? (len >= 20 && data[19] == 0)
                        : (pageMode && len >= 15 && data[14] == 0);
                    if (bundleTransactionActive_ && (!pageMode || transactionStart)) {
                        LOG_ERR("FRAME", "%s", "new transaction rejected while bundle is staging");
                        abortTransfer();
                        i = len;
                        break;
                    }
                    const uint16_t expectedPacketLength = groupMode ? 24 : (pageMode ? 21 : 14);
                    if (len != expectedPacketLength) {
                        static bool logged = false;
                        if (!logged) {
                            LOG_ERR("FRAME", "%s", "frame begin: wrong length");
                            logged = true;
                        }
                        abortTransfer();
                        i = len;
                        break;
                    }
                    state_ = State::Begin;
                    pageMode_ = pageMode;
                    groupMode_ = groupMode;
                    beginHeaderLength_ = groupMode ? 23 : (pageMode ? 20 : 13);
                    hdrIdx_ = 0;
                    bufIdx_ = 0;
                } else if (byte == kOpClear) {
                    if (len != 2) {
                        static bool logged = false;
                        if (!logged) {
                            LOG_ERR("FRAME", "%s", "frame clear: wrong length");
                            logged = true;
                        }
                        abortTransfer();
                        i = len;
                        break;
                    }
                    state_ = State::Clear;
                    hdrIdx_ = 0;
                } else {
                    static bool logged = false;
                    if (!logged) {
                        snprintf(logBuf, sizeof(logBuf), "unknown opcode 0x%02X in Idle", byte);
                        LOG_ERR("FRAME", "%s", logBuf);
                        logged = true;
                    }
                    abortTransfer();
                    i = len;  // Reject the entire malformed ATT write, including trailing bytes.
                }
                ++i;
                break;

            case State::Begin:
                if (hdrIdx_ >= kMaxHdrBytes) {
                    LOG_ERR("FRAME", "%s", "frame abort: header overrun");
                    abortTransfer();
                    i = len;
                    break;
                }
                hdrBuf_[hdrIdx_++] = byte;
                if (hdrIdx_ == beginHeaderLength_) {
                    x_ = static_cast<uint16_t>(hdrBuf_[0]) | (static_cast<uint16_t>(hdrBuf_[1]) << 8);
                    y_ = static_cast<uint16_t>(hdrBuf_[2]) | (static_cast<uint16_t>(hdrBuf_[3]) << 8);
                    w_ = static_cast<uint16_t>(hdrBuf_[4]) | (static_cast<uint16_t>(hdrBuf_[5]) << 8);
                    h_ = static_cast<uint16_t>(hdrBuf_[6]) | (static_cast<uint16_t>(hdrBuf_[7]) << 8);
                    format_ = hdrBuf_[8];
                    dataLength_ = static_cast<uint32_t>(hdrBuf_[9]) |
                                  (static_cast<uint32_t>(hdrBuf_[10]) << 8) |
                                  (static_cast<uint32_t>(hdrBuf_[11]) << 16) |
                                  (static_cast<uint32_t>(hdrBuf_[12]) << 24);
                    if (pageMode_) {
                        pageIndex_ = hdrBuf_[13];
                        pageCount_ = hdrBuf_[14];
                        activePage_ = hdrBuf_[15];
                        if (groupMode_) {
                            groupStart_ = hdrBuf_[16]; groupCount_ = hdrBuf_[17]; groupOffset_ = hdrBuf_[18];
                            expectedCrc_ = static_cast<uint32_t>(hdrBuf_[19]) |
                                           (static_cast<uint32_t>(hdrBuf_[20]) << 8) |
                                           (static_cast<uint32_t>(hdrBuf_[21]) << 16) |
                                           (static_cast<uint32_t>(hdrBuf_[22]) << 24);
                        } else {
                            expectedCrc_ = static_cast<uint32_t>(hdrBuf_[16]) |
                                           (static_cast<uint32_t>(hdrBuf_[17]) << 8) |
                                           (static_cast<uint32_t>(hdrBuf_[18]) << 16) |
                                           (static_cast<uint32_t>(hdrBuf_[19]) << 24);
                        }
                    }

                    if (format_ != 0) {
                        static bool logged = false;
                        if (!logged) {
                            LOG_ERR("FRAME", "%s", "frame begin: unsupported format");
                            logged = true;
                        }
                        abortTransfer();
                        i = len;
                        break;
                    }

                    if (w_ == 0 || h_ == 0) {
                        static bool logged = false;
                        if (!logged) {
                            LOG_ERR("FRAME", "%s", "frame begin: zero dimension");
                            logged = true;
                        }
                        abortTransfer();
                        i = len;
                        break;
                    }

                    uint32_t xEnd = static_cast<uint32_t>(x_) + static_cast<uint32_t>(w_);
                    uint32_t yEnd = static_cast<uint32_t>(y_) + static_cast<uint32_t>(h_);
                    if (xEnd > static_cast<uint32_t>(sink_->width()) ||
                        yEnd > static_cast<uint32_t>(sink_->height())) {
                        static bool logged = false;
                        if (!logged) {
                            LOG_ERR("FRAME", "%s", "frame begin: rect out of bounds");
                            logged = true;
                        }
                        abortTransfer();
                        i = len;
                        break;
                    }

                    uint16_t rowBytes = (w_ + 7) / 8;
                    if (rowBytes > kMaxRowBytes) {
                        static bool logged = false;
                        if (!logged) {
                            LOG_ERR("FRAME", "%s", "frame begin: row too wide");
                            logged = true;
                        }
                        abortTransfer();
                        i = len;
                        break;
                    }

                    uint32_t expectedLength = static_cast<uint32_t>(rowBytes) * h_;
                    if (dataLength_ != expectedLength) {
                        static bool logged = false;
                        if (!logged) {
                            snprintf(logBuf, sizeof(logBuf),
                                     "frame begin: length mismatch want=%lu got=%lu",
                                     expectedLength, dataLength_);
                            LOG_ERR("FRAME", "%s", logBuf);
                            logged = true;
                        }
                        abortTransfer();
                        i = len;
                        break;
                    }

                    bool wholePanel = (x_ == 0 && y_ == 0 &&
                                       w_ == sink_->width() && h_ == sink_->height());
                    if (pageMode_) {
                        bool validBundle = wholePanel && pageCount_ > 0 && pageIndex_ < pageCount_ &&
                                           activePage_ < pageCount_;
                        if (groupMode_) {
                            validBundle = validBundle && groupCount_ > 0 &&
                                groupCount_ <= SidecarSink::kMaxGroupPages && groupStart_ < pageCount_ &&
                                static_cast<uint16_t>(groupStart_) + groupCount_ <= pageCount_ &&
                                groupOffset_ < groupCount_ && pageIndex_ == groupStart_ + groupOffset_;
                            if (validBundle && groupOffset_ == 0) {
                                validBundle = !bundleTransactionActive_ &&
                                    sink_->beginPageGroup(pageCount_, activePage_, groupStart_, groupCount_);
                                if (validBundle) {
                                    bundleNextPage_ = groupStart_;
                                    bundleActivePage_ = activePage_;
                                    bundlePageCount_ = pageCount_;
                                    bundleGroupStart_ = groupStart_;
                                    bundleGroupCount_ = groupCount_;
                                    bundleTransactionActive_ = true;
                                }
                            } else if (validBundle) {
                                validBundle = bundleTransactionActive_ && pageIndex_ == bundleNextPage_ &&
                                    activePage_ == bundleActivePage_ && pageCount_ == bundlePageCount_ &&
                                    groupStart_ == bundleGroupStart_ && groupCount_ == bundleGroupCount_;
                            }
                        } else {
                            validBundle = validBundle && pageCount_ <= SidecarSink::kMaxStoredPages;
                            if (validBundle && pageIndex_ == 0) {
                                validBundle = !bundleTransactionActive_ &&
                                    sink_->beginPageBundle(pageCount_, activePage_);
                                if (validBundle) {
                                    bundleNextPage_ = 0;
                                    bundleActivePage_ = activePage_;
                                    bundlePageCount_ = pageCount_;
                                    bundleTransactionActive_ = true;
                                }
                            } else if (validBundle) {
                                validBundle = bundleTransactionActive_ && pageIndex_ == bundleNextPage_ &&
                                    activePage_ == bundleActivePage_ && pageCount_ == bundlePageCount_;
                            }
                        }
                        if (!validBundle) {
                            LOG_ERR("FRAME", "%s", "page begin: invalid bundle metadata or storage unavailable");
                            abortTransfer();
                            i = len;
                            break;
                        }
                    }
                    if (wholePanel) {
                        sink_->beginFrame();
                    }

                    dataReceived_ = 0;
                    nextSeq_ = 0;
                    rowY_ = 0;
                    bufIdx_ = 0;
                    runningCrc_ = 0xFFFFFFFFu;
                    frameStartTime = millis();

                    snprintf(logBuf, sizeof(logBuf),
                             "frame begin x=%u y=%u w=%u h=%u len=%lu",
                             x_, y_, w_, h_, dataLength_);
                    LOG_INF("FRAME", "%s", logBuf);

                    state_ = State::TransferWait;
                }
                ++i;
                break;

            case State::TransferWait:
                if (byte == kOpData) {
                    if (len - i < 3) {
                        LOG_ERR("FRAME", "%s", "frame abort: short data header");
                        abortTransfer();
                        i = len;
                        break;
                    }
                    state_ = State::DataHdr;
                    hdrIdx_ = 0;
                } else if (byte == kOpEnd) {
                    if (len - i != 2) {
                        LOG_ERR("FRAME", "%s", "frame abort: malformed end packet");
                        abortTransfer();
                        i = len;
                        break;
                    }
                    state_ = State::End;
                    hdrIdx_ = 0;
                } else if (byte == kOpBegin || byte == kOpBeginPage || byte == kOpBeginGroupPage) {
                    const bool pageMode = byte == kOpBeginPage || byte == kOpBeginGroupPage;
                    const bool groupMode = byte == kOpBeginGroupPage;
                    const uint16_t expectedPacketLength = groupMode ? 24 : (pageMode ? 21 : 14);
                    if (len - i != expectedPacketLength) {
                        static bool logged = false;
                        if (!logged) {
                            LOG_ERR("FRAME", "%s", "frame begin: wrong length");
                            logged = true;
                        }
                        abortTransfer();
                        i = len;
                        break;
                    }
                    LOG_ERR("FRAME", "%s", "frame abort: new begin during transfer");
                    abortTransfer();
                    i = len;
                    break;
                } else if (byte == kOpClear) {
                    if (len - i != 2) {
                        static bool logged = false;
                        if (!logged) {
                            LOG_ERR("FRAME", "%s", "frame clear: wrong length");
                            logged = true;
                        }
                        abortTransfer();
                        i = len;
                        break;
                    }
                    LOG_ERR("FRAME", "%s", "frame abort: clear during transfer");
                    abortTransfer();
                    i = len;
                    break;
                } else {
                    static bool logged = false;
                    if (!logged) {
                        snprintf(logBuf, sizeof(logBuf),
                                 "unknown opcode 0x%02X in TransferWait", byte);
                        LOG_ERR("FRAME", "%s", logBuf);
                        logged = true;
                    }
                    abortTransfer();
                    i = len;
                    break;
                }
                ++i;
                break;

            case State::DataHdr:
                if (hdrIdx_ >= kMaxHdrBytes) {
                    LOG_ERR("FRAME", "%s", "frame abort: header overrun");
                    abortTransfer();
                    i = len;
                    break;
                }
                hdrBuf_[hdrIdx_++] = byte;
                if (hdrIdx_ == 2) {
                    uint16_t seq = static_cast<uint16_t>(hdrBuf_[0]) |
                                   (static_cast<uint16_t>(hdrBuf_[1]) << 8);
                    if (seq != nextSeq_) {
                        snprintf(logBuf, sizeof(logBuf),
                                 "frame abort: seq gap want=%u got=%u", nextSeq_, seq);
                        LOG_ERR("FRAME", "%s", logBuf);
                        abortTransfer();
                        i = len;
                        break;
                    }
                    ++nextSeq_;
                    state_ = State::Data;
                }
                ++i;
                break;

            case State::Data: {
                if (dataReceived_ >= dataLength_) {
                    LOG_ERR("FRAME", "%s", "frame abort: payload overrun");
                    abortTransfer();
                    i = len;
                    break;
                }
                uint16_t rowBytes = (w_ + 7) / 8;
                rowBuf_[bufIdx_++] = byte;
                runningCrc_ = crc32Byte(runningCrc_, byte);
                ++dataReceived_;
                if (bufIdx_ == rowBytes) {
                    if (!sink_->blit1bpp(x_, y_ + rowY_, w_, 1, rowBuf_)) {
                        snprintf(logBuf, sizeof(logBuf),
                                 "frame abort: blit rejected row=%u", rowY_);
                        LOG_ERR("FRAME", "%s", logBuf);
                        abortTransfer();
                        i = len;
                        break;
                    }
                    ++rowY_;
                    bufIdx_ = 0;
                }
                ++i;
                break;
            }

            case State::End: {
                // refresh byte accepted and ignored in v1 - the sink owns the refresh mode
                if (dataReceived_ != dataLength_) {
                    snprintf(logBuf, sizeof(logBuf),
                             "frame abort: short len want=%lu got=%lu",
                             dataLength_, dataReceived_);
                    LOG_ERR("FRAME", "%s", logBuf);
                    abortTransfer();
                    i = len;
                    break;
                }
                if (pageMode_) {
                    const uint32_t actualCrc = runningCrc_ ^ 0xFFFFFFFFu;
                    if (actualCrc != expectedCrc_ || !sink_->storePage(pageIndex_, actualCrc)) {
                        LOG_ERR("FRAME", "%s", "frame abort: page checksum or storage failure");
                        abortTransfer();
                        i = len;
                        break;
                    }
                    bundleNextPage_ = static_cast<uint8_t>(pageIndex_ + 1);
                    const bool transactionComplete = groupMode_
                        ? (groupOffset_ + 1 == groupCount_)
                        : (pageIndex_ + 1 == pageCount_);
                    if (transactionComplete) {
                        if (!sink_->commitPageBundle()) {
                            LOG_ERR("FRAME", "%s", "frame abort: bundle commit failed");
                            abortTransfer();
                            i = len;
                            break;
                        }
                        // A deferred sink has verified and staged the transaction but has not
                        // published or presented it. Its owner reports completion after the later
                        // cycle-boundary activation; the parser must not expose false success.
                        frameComplete_ = !sink_->hasPendingBundle();
                        pageMode_ = false;
                        groupMode_ = false;
                        bundleTransactionActive_ = false;
                        bundleNextPage_ = 0;
                        bundlePageCount_ = 0;
                        bundleGroupStart_ = 0;
                        bundleGroupCount_ = 0;
                    }
                } else {
                    sink_->present();
                    frameComplete_ = true;
                    pageMode_ = false;
                }
                uint32_t elapsed = millis() - frameStartTime;
                snprintf(logBuf, sizeof(logBuf),
                         frameComplete_ ? "frame complete rows=%u bytes=%lu ms=%lu"
                                        : "frame accepted rows=%u bytes=%lu ms=%lu",
                         rowY_, dataReceived_, elapsed);
                LOG_INF("FRAME", "%s", logBuf);
                state_ = State::Idle;
                hdrIdx_ = 0;
                bufIdx_ = 0;
                i = len;
                break;
            }

            case State::Clear:
                // colour byte accepted and ignored in v1 (sink clears white)
                sink_->beginFrame();
                sink_->present();
                frameComplete_ = true;
                state_ = State::Idle;
                hdrIdx_ = 0;
                bufIdx_ = 0;
                i = len;
                break;
        }
    }

    if (state_ == State::Data) {
        state_ = State::TransferWait;
    }
}

bool SidecarFrameProtocol::inTransfer() const {
    if (sink_ == nullptr) return false;
    return state_ == State::TransferWait || state_ == State::DataHdr || state_ == State::Data;
}

bool SidecarFrameProtocol::consumeFrameComplete() {
    const bool complete = frameComplete_;
    frameComplete_ = false;
    return complete;
}

void SidecarFrameProtocol::reset() {
    abortTransfer();
    frameComplete_ = false;
}

void SidecarFrameProtocol::abortTransfer() {
    if (sink_ != nullptr) sink_->abandonPageBundle();
    state_ = State::Idle;
    hdrIdx_ = 0;
    bufIdx_ = 0;
    dataReceived_ = 0;
    pageMode_ = false;
    groupMode_ = false;
    bundleNextPage_ = 0;
    bundlePageCount_ = 0;
    bundleGroupStart_ = 0;
    bundleGroupCount_ = 0;
    bundleTransactionActive_ = false;
    frameComplete_ = false;
}

#else // !SIDECAR_LINK

SidecarFrameProtocol::SidecarFrameProtocol(SidecarSink* /*sink*/)
    : sink_(nullptr),
      frameComplete_(false),
      state_(State::Idle),
      hdrIdx_(0),
      x_(0), y_(0), w_(0), h_(0),
      format_(0),
      pageMode_(false), groupMode_(false), beginHeaderLength_(13), pageIndex_(0), pageCount_(0), activePage_(0),
      bundleNextPage_(0), bundleActivePage_(0), bundlePageCount_(0),
      groupStart_(0), groupCount_(0), groupOffset_(0),
      bundleGroupStart_(0), bundleGroupCount_(0), bundleTransactionActive_(false),
      expectedCrc_(0), runningCrc_(0xFFFFFFFFu),
      dataLength_(0), dataReceived_(0),
      nextSeq_(0), rowY_(0), bufIdx_(0) {
}

void SidecarFrameProtocol::onWrite(const uint8_t* /*data*/, uint16_t /*len*/) {
}

bool SidecarFrameProtocol::inTransfer() const {
    return false;
}

bool SidecarFrameProtocol::consumeFrameComplete() {
    return false;
}

void SidecarFrameProtocol::reset() {
}

#endif
