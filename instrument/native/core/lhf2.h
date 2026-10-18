#ifndef OBD_LHF2_H
#define OBD_LHF2_H

#include <stddef.h>
#include <stdint.h>

#define OBD_LHF2_HEADER_SIZE 24u
#define OBD_LHF2_MAX_PAYLOAD (2u * 1024u * 1024u)

typedef struct {
    uint32_t bytes, sequence, pts_ms, key_frame, crc;
} obd_lhf2_frame;

typedef struct {
    int has_previous;
    uint32_t sequence, pts_ms;
} obd_lhf2_state;

/* No authentication or rendering in this library. All wire integers are big-endian. */
uint32_t obd_crc32(const uint8_t *data, size_t size);
int obd_lhf2_header(const uint8_t *header, size_t size, obd_lhf2_frame *frame);
/* Validation never advances state. Commit only after the chosen sink accepts the frame. */
int obd_lhf2_validate(const obd_lhf2_state *state, const obd_lhf2_frame *frame,
                      const uint8_t *payload, size_t size);
void obd_lhf2_commit(obd_lhf2_state *state, const obd_lhf2_frame *frame);

#endif
