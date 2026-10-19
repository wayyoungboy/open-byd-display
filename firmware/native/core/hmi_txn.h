#ifndef OBD_HMI_TXN_H
#define OBD_HMI_TXN_H
#include <stdint.h>
enum { OBD_HMI_THEME=0,OBD_HMI_CENTER=1,OBD_HMI_MODE=2 };
enum { OBD_HMI_IDLE=0,OBD_HMI_STARTING=1,OBD_HMI_PLAYING=2,OBD_HMI_RESTORING=3,OBD_HMI_RECOVERY=4 };
typedef struct {
    uint64_t epoch,lease_until;
    uint32_t revision,phase,changed,requested,waiting,yielded;
    int saved[3],desired[3],owned[3]; uint64_t observed[3],receipt[3];
} obd_hmi_state;
typedef struct {
    void *context;
    int (*read)(void *,unsigned,int *,uint64_t *);
    /* Atomic CAS receipt: return a unique committed revision for THIS request.
     * Zero means submitted; read() must still confirm that exact receipt/value.
     * A merely queued write or unknown revision cannot return success. */
    int (*write)(void *,unsigned,int expected,uint64_t revision,int desired,uint64_t *receipt);
    int (*video_ready)(void *);
    int (*video_stop)(void *);
} obd_hmi_api;
int obd_hmi_begin(obd_hmi_state *,uint64_t epoch,uint64_t now,unsigned lease_ms,const int desired[3],const obd_hmi_api *);
int obd_hmi_step(obd_hmi_state *,uint64_t epoch,uint32_t expected_revision,uint64_t now,const obd_hmi_api *);
int obd_hmi_end(obd_hmi_state *,uint64_t epoch,uint32_t expected_revision,const obd_hmi_api *);
#endif
