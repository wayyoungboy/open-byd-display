#ifndef OBD_SHA256_H
#define OBD_SHA256_H
#include <stdint.h>
#include <stddef.h>
typedef struct { uint32_t h[8]; uint64_t bytes; unsigned used; uint8_t block[64]; } obd_sha256;
void obd_sha256_init(obd_sha256 *);
void obd_sha256_update(obd_sha256 *, const void *, size_t);
void obd_sha256_final(obd_sha256 *, uint8_t digest[32]);
#endif
