#ifndef OBD_UPDATE_REQUEST_H
#define OBD_UPDATE_REQUEST_H
#include <stddef.h>
#include <stdint.h>
#define OBD_UPDATE_LINE 1024u
enum { OBD_UPDATE_INFO=1,OBD_UPDATE_UPLOAD,OBD_UPDATE_STATUS,OBD_UPDATE_APPLY,OBD_UPDATE_CANCEL };
typedef struct {
    unsigned op,bytes,parked;
    char id[33],target[32],sha256[65],baseline[65];
} obd_update_request;
/* Deliberately restricted JSON object: fixed ASCII keys/scalars, no nesting,
 * escapes, duplicate/unknown keys, floats, sign, overflow or trailing data. */
int obd_update_parse(obd_update_request *,const char *,size_t);
#endif
