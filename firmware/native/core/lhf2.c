#include "lhf2.h"
#include <string.h>

static uint32_t be32(const uint8_t *p) {
    return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}

uint32_t obd_crc32(const uint8_t *data, size_t size) {
    uint32_t crc = UINT32_MAX;
    for (size_t i = 0; i < size; ++i) {
        crc ^= data[i];
        for (unsigned bit = 0; bit < 8; ++bit)
            crc = (crc >> 1) ^ (UINT32_C(0xedb88320) & (0u - (crc & 1u)));
    }
    return crc ^ UINT32_MAX;
}

int obd_lhf2_header(const uint8_t *header, size_t size, obd_lhf2_frame *frame) {
    if (!header || !frame || size != OBD_LHF2_HEADER_SIZE || memcmp(header, "LHF2", 4)) return -1;
    obd_lhf2_frame candidate = {be32(header + 4), be32(header + 8), be32(header + 12), be32(header + 16), be32(header + 20)};
    if (candidate.bytes > OBD_LHF2_MAX_PAYLOAD || candidate.key_frame > 1) return -1;
    if (!candidate.bytes && (candidate.sequence != UINT32_MAX || candidate.pts_ms || candidate.key_frame || candidate.crc)) return -1;
    *frame = candidate;
    return 0;
}

static int nal_mask(const uint8_t *p, size_t size, unsigned *mask) {
    size_t i = 0;
    int seen = 0;
    *mask = 0;
    while (i < size) {
        size_t start = i;
        int found = 0;
        for (; i + 2 < size; ++i) {
            if (p[i] || p[i + 1]) continue;
            if (p[i + 2] == 1) { i += 3; found = 1; break; }
            if (i + 3 < size && !p[i + 2] && p[i + 3] == 1) { i += 4; found = 1; break; }
        }
        if (!found || i >= size) return -1;
        if (!seen) {
            /* Only zero padding may precede the first Annex-B start code. */
            for (size_t j = start; j < i - 3; ++j) if (p[j]) return -1;
        }
        uint8_t type = p[i] & 31u;
        if (p[i] & 128u) return -1;
        switch (type) {
            case 1: *mask |= 8; break;
            case 5: *mask |= 1; break;
            case 6: case 9: case 12: break;
            case 7: *mask |= 2; break;
            case 8: *mask |= 4; break;
            default: return -1;
        }
        seen = 1;
        /* Scan NAL payload until the next start code, or the end of this AU. */
        ++i;
        while (i + 2 < size && !(p[i] == 0 && p[i + 1] == 0 &&
               (p[i + 2] == 1 || (i + 3 < size && p[i + 2] == 0 && p[i + 3] == 1)))) ++i;
        if (i + 2 >= size) return 0;
    }
    return seen ? 0 : -1;
}

int obd_lhf2_validate(const obd_lhf2_state *state, const obd_lhf2_frame *frame,
                      const uint8_t *payload, size_t size) {
    if (!state || !frame || frame->bytes != size || size > OBD_LHF2_MAX_PAYLOAD || frame->key_frame > 1) return -1;
    if (!size) return frame->sequence == UINT32_MAX && !frame->pts_ms && !frame->key_frame && !frame->crc ? 0 : -1;
    if (!payload || obd_crc32(payload, size) != frame->crc) return -1;
    uint32_t sequence_delta = frame->sequence - state->sequence;
    uint32_t pts_delta = frame->pts_ms - state->pts_ms;
    if (state->has_previous && (!sequence_delta || sequence_delta >= UINT32_C(0x80000000) || pts_delta >= UINT32_C(0x80000000))) return -1;
    unsigned mask;
    if (nal_mask(payload, size, &mask) || !(mask & 9u) || ((mask & 1u) != frame->key_frame)) return -1;
    if ((!state->has_previous || sequence_delta != 1) && (mask & 7u) != 7u) return -1;
    return 0;
}

void obd_lhf2_commit(obd_lhf2_state *state, const obd_lhf2_frame *frame) {
    if (!state || !frame || !frame->bytes) return;
    state->has_previous = 1;
    state->sequence = frame->sequence;
    state->pts_ms = frame->pts_ms;
}
