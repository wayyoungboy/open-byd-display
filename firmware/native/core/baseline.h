#ifndef OBD_BASELINE_H
#define OBD_BASELINE_H
#include <stdint.h>
typedef struct { const char *path; uint32_t bytes; const char *sha256; } obd_baseline_file;
typedef struct {
    void *context;
    int (*open)(void *,const char *,uint32_t expected_bytes);
    int (*read)(void *,int,void *,unsigned);
    int (*close)(void *,int);
} obd_baseline_io;
/* Read-only fingerprints, NOT board attestation or an installation permit.
 * At most 32 MiB per regular input; no retry loop on I/O failures. */
int obd_baseline_check(const obd_baseline_file *,unsigned,const obd_baseline_io *,
                       const volatile int *cancel,unsigned *failed_index);
#endif
