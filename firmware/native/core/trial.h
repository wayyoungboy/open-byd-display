#ifndef OBD_TRIAL_H
#define OBD_TRIAL_H
#include <stdint.h>

/* In-memory policy, NOT an on-disk ABI. Platform storage must decode/validate its
 * own durable record; a missing/corrupt existing record must never be reset. */
typedef struct {
    unsigned present;
    char package_id[33];
    uint16_t version[3];
    uint8_t tree_sha256[32];
} obd_trial_slot;
typedef struct {
    uint32_t format, revision;
    uint8_t identity_sha256[32];
    uint16_t highwater[3];
    unsigned highwater_present;
    obd_trial_slot current, candidate;
    unsigned attempted;
    uint8_t nonce[16];
} obd_trial_state;
typedef struct {
    void *context;
    /* Reverify signed origin, target/hardware identity and complete file tree.
     * Zero means verified; no implementation may substitute file existence. */
    int (*verify)(void *, const uint8_t identity[32], const obd_trial_slot *);
    /* Compare-and-swap with durable commit of the ENTIRE record. A nonzero or
     * ambiguous result blocks handoff; caller must reload before retrying. */
    int (*commit)(void *, uint32_t expected_revision, const obd_trial_state *);
    /* Actual platform health for this attempted package, not merely ACK/POST. */
    int (*healthy)(void *, const obd_trial_slot *);
} obd_trial_api;
enum { OBD_TRIAL_CURRENT=0, OBD_TRIAL_CANDIDATE=1, OBD_TRIAL_OEM=2,
       OBD_TRIAL_INVALID=-1, OBD_TRIAL_VERIFY=-2, OBD_TRIAL_COMMIT=-3,
       OBD_TRIAL_HEALTH=-4 };
int obd_trial_validate(const obd_trial_state *);
int obd_trial_activate(obd_trial_state *, const obd_trial_slot *,
                       const uint8_t nonce[16], const obd_trial_api *);
/* Selects a verified slot only AFTER persisting the attempt/rollback. Never
 * executes a program, touches a boot record, or claims production readiness. */
int obd_trial_boot(obd_trial_state *, obd_trial_slot *selected, const obd_trial_api *);
int obd_trial_confirm(obd_trial_state *, const char package_id[33],
                      const uint8_t nonce[16], const obd_trial_api *);
#endif
