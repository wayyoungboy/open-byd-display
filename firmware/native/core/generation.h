#ifndef OBD_GENERATION_H
#define OBD_GENERATION_H
#include <stdint.h>
#include <stddef.h>
#include "trial.h"
#define OBD_GEN_HEADER 208u
#define OBD_GEN_ROW 128u
#define OBD_GEN_FILES 16u
#define OBD_GEN_PAYLOAD (16u*1024*1024)
typedef struct { char name[64]; uint32_t bytes,mode,offset; uint8_t sha256[32]; } obd_generation_file;
typedef struct {
    obd_trial_slot slot;
    char target[32];
    uint8_t baseline[32];
    unsigned count;
    const uint8_t *payload;
    obd_generation_file files[OBD_GEN_FILES];
} obd_generation;
/* Verifies Ed25519 over the canonical metadata and SHA256 over EVERY file.
 * Flat ordinary files only; no executable installer scripts or path traversal. */
int obd_generation_verify(obd_generation *,const uint8_t *,size_t,
                          const uint8_t public_key[32],const char *target,
                          const uint8_t baseline[32]);
void obd_generation_hex(char *,const uint8_t *,unsigned);
int obd_generation_unhex(uint8_t *,const char *,unsigned);
#endif
