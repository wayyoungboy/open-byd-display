#ifndef OBD_INSPECT_H
#define OBD_INSPECT_H
#include "media.h"
typedef struct { int id,width,height; } obd_inspect_display;
typedef struct {
    int stage, cleanup_failed, count, renderer_connected;
    obd_inspect_display displays[8];
} obd_inspect_result;
/* Explicit opt-in: creates only a temporary Screen context and mmr connection.
 * No windows, playback, USB, filesystem/boot changes or OEM HMI writes. */
int obd_platform_inspect(const obd_media_api *, obd_inspect_result *);
#endif
