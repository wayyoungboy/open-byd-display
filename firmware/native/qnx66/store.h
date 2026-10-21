#ifndef OBD_STORE_H
#define OBD_STORE_H
#include "generation.h"
enum { OBD_STORE_BUSY=-2 };
typedef struct {
    char root[256],target[32];
    uint8_t public_key[32],baseline[32],identity[32];
    int lock_fd,poisoned;
    obd_trial_state state;
} obd_store;
int obd_store_init(const char *root,const char *target,const uint8_t baseline[32],const uint8_t public_key[32]);
int obd_store_open(obd_store *,const char *,const char *,const uint8_t baseline[32],const uint8_t public_key[32]);
void obd_store_close(obd_store *);
int obd_store_stage(obd_store *,const uint8_t *,size_t,char package_id[33]);
int obd_store_activate(obd_store *,const char *,const uint8_t nonce[16]);
int obd_store_select(obd_store *,obd_trial_slot *,char directory[384]);
int obd_store_verify_slot(void *,const uint8_t identity[32],const obd_trial_slot *);
/* Called only by a supervisor with an independent health adapter. */
int obd_store_confirm(obd_store *,const char *,const uint8_t nonce[16],int (*healthy)(void *,const obd_trial_slot *),void *);
int obd_store_current_profile(obd_store *,const obd_trial_slot *,uint8_t profile[64]);
int obd_store_inspect(obd_store *,const char *id,obd_trial_slot *,uint8_t profile[64]);
#endif
