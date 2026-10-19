#include "trial.h"
#include <string.h>

static int nonzero(const uint8_t *p, unsigned n) {
    unsigned value=0; for(unsigned i=0;i<n;i++) value|=p[i]; return value!=0;
}
static int compare(const uint16_t a[3], const uint16_t b[3]) {
    for(unsigned i=0;i<3;i++) if(a[i]!=b[i]) return a[i]>b[i]?1:-1;
    return 0;
}
static int identifier(const char *p) {
    for(unsigned i=0;i<32;i++) if(!((p[i]>='0'&&p[i]<='9')||(p[i]>='a'&&p[i]<='f'))) return 0;
    return p[32]==0;
}
static int slot(const obd_trial_slot *s) {
    if(s->present>1) return 0;
    if(s->present) return identifier(s->package_id) && nonzero(s->tree_sha256,32);
    return !nonzero((const uint8_t *)s->package_id,33) &&
        !nonzero(s->tree_sha256,32) && !s->version[0] && !s->version[1] && !s->version[2];
}
int obd_trial_validate(const obd_trial_state *s) {
    if(!s || s->format!=1 || !s->revision || !nonzero(s->identity_sha256,32) ||
       !slot(&s->current) || !slot(&s->candidate) || s->attempted>1 || s->highwater_present>1 ||
       compare(s->current.version,s->highwater)>0) return OBD_TRIAL_INVALID;
    if(!s->highwater_present && (s->current.present || s->candidate.present ||
       s->highwater[0] || s->highwater[1] || s->highwater[2])) return OBD_TRIAL_INVALID;
    if(s->candidate.present) {
        if(!nonzero(s->nonce,16) || compare(s->candidate.version,s->highwater)!=0 ||
           (s->current.present && (!strcmp(s->current.package_id,s->candidate.package_id) ||
              compare(s->candidate.version,s->current.version)<=0))) return OBD_TRIAL_INVALID;
    } else if(s->attempted || nonzero(s->nonce,16)) return OBD_TRIAL_INVALID;
    return 0;
}
static int api_valid(const obd_trial_api *a) { return a && a->verify && a->commit; }
static int commit(obd_trial_state *s, obd_trial_state *next, const obd_trial_api *a) {
    if(s->revision==UINT32_MAX) return OBD_TRIAL_INVALID;
    next->revision=s->revision+1;
    if(obd_trial_validate(next)) return OBD_TRIAL_INVALID;
    if(a->commit(a->context,s->revision,next)) return OBD_TRIAL_COMMIT;
    *s=*next; return 0;
}
int obd_trial_activate(obd_trial_state *s, const obd_trial_slot *candidate,
                       const uint8_t nonce[16], const obd_trial_api *a) {
    if(obd_trial_validate(s) || !api_valid(a) || !candidate || !slot(candidate) ||
       !candidate->present || !nonce || !nonzero(nonce,16) || s->candidate.present ||
       (s->highwater_present && compare(candidate->version,s->highwater)<=0) ||
       (s->current.present && !strcmp(s->current.package_id,candidate->package_id))) return OBD_TRIAL_INVALID;
    if(a->verify(a->context,s->identity_sha256,candidate)) return OBD_TRIAL_VERIFY;
    obd_trial_state next=*s; next.candidate=*candidate; next.attempted=0;
    memcpy(next.nonce,nonce,16); memcpy(next.highwater,candidate->version,sizeof(next.highwater));
    next.highwater_present=1;
    return commit(s,&next,a);
}
int obd_trial_boot(obd_trial_state *s, obd_trial_slot *selected, const obd_trial_api *a) {
    if(selected) memset(selected,0,sizeof(*selected));
    if(obd_trial_validate(s) || !selected || !api_valid(a)) return OBD_TRIAL_INVALID;
    if(s->candidate.present) {
        obd_trial_state next=*s;
        if(!s->attempted && !a->verify(a->context,s->identity_sha256,&s->candidate)) {
            next.attempted=1;
            int rc=commit(s,&next,a); if(rc) return rc;
            *selected=s->candidate; return OBD_TRIAL_CANDIDATE;
        }
        /* Unconfirmed or corrupt trial: persist removal without reducing the
         * high-water version BEFORE returning the previous confirmed slot. */
        memset(&next.candidate,0,sizeof(next.candidate)); next.attempted=0;
        memset(next.nonce,0,sizeof(next.nonce));
        int rc=commit(s,&next,a); if(rc) return rc;
    }
    if(!s->current.present) return OBD_TRIAL_OEM;
    if(a->verify(a->context,s->identity_sha256,&s->current)) return OBD_TRIAL_VERIFY;
    *selected=s->current; return OBD_TRIAL_CURRENT;
}
int obd_trial_confirm(obd_trial_state *s, const char package_id[33],
                      const uint8_t nonce[16], const obd_trial_api *a) {
    if(obd_trial_validate(s) || !api_valid(a) || !a->healthy || !package_id ||
       !identifier(package_id) || !nonce || !s->candidate.present || !s->attempted ||
       strcmp(package_id,s->candidate.package_id) || memcmp(nonce,s->nonce,16)) return OBD_TRIAL_INVALID;
    if(a->verify(a->context,s->identity_sha256,&s->candidate)) return OBD_TRIAL_VERIFY;
    if(a->healthy(a->context,&s->candidate)) return OBD_TRIAL_HEALTH;
    obd_trial_state next=*s; next.current=next.candidate;
    memset(&next.candidate,0,sizeof(next.candidate)); next.attempted=0;
    memset(next.nonce,0,sizeof(next.nonce));
    return commit(s,&next,a);
}
