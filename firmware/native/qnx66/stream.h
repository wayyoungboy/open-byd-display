#ifndef OBD_STREAM_H
#define OBD_STREAM_H
#include "media.h"
/* Serialized session. Caller owns nonblocking descriptors and their lifetime. */
int obd_stream_run(obd_media *, const obd_media_api *, const obd_media_config *,
                   int input, int output, int continuous, const volatile int *cancel);
#endif
