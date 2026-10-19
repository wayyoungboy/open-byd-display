#ifndef OBD_TRIAL_RECORD_H
#define OBD_TRIAL_RECORD_H
#include "trial.h"
#define OBD_TRIAL_RECORD_BYTES 256u
int obd_trial_encode(uint8_t out[OBD_TRIAL_RECORD_BYTES],const obd_trial_state *);
int obd_trial_decode(obd_trial_state *,const uint8_t *,unsigned bytes);
#endif
