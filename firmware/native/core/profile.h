#ifndef OBD_PROFILE_H
#define OBD_PROFILE_H
#include <stdint.h>
/* Canonical signed profile.dat: fixed 64 bytes, no executable/script fields. */
typedef struct {
    unsigned enabled, display, physical_width, physical_height;
    unsigned x,y,width,height,zorder,trial_seconds;
    char address[16];
    char socket_environment[128]; /* Empty or explicitly signed own SOCK prefix. */
} obd_service_profile;
int obd_profile_decode(obd_service_profile *,const uint8_t [64],const char *target);
#endif
