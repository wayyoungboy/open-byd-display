#include "hmi_txn.h"
#include <string.h>
static int api(const obd_hmi_api *a) { return a && a->read && a->write && a->video_ready && a->video_stop; }
static int failure(obd_hmi_state *s,const obd_hmi_api *a) {
    s->phase=OBD_HMI_RECOVERY; (void)a->video_stop(a->context); return -1;
}
static int fence(obd_hmi_state *s,uint64_t epoch,uint32_t revision,const obd_hmi_api *a) {
    return !s || !api(a) || !epoch || epoch!=s->epoch || revision!=s->revision || s->revision==UINT32_MAX ||
        s->phase<OBD_HMI_STARTING || s->phase>OBD_HMI_RESTORING?-1:0;
}
int obd_hmi_begin(obd_hmi_state *s,uint64_t epoch,uint64_t now,unsigned lease,const int desired[3],const obd_hmi_api *a) {
    if(!s || !api(a) || !epoch || epoch<=s->epoch || !now || !lease || lease>30000 || now>UINT64_MAX-lease || !desired || s->phase!=OBD_HMI_IDLE) return -1;
    obd_hmi_state n; memset(&n,0,sizeof(n)); n.epoch=epoch; n.lease_until=now+lease; n.revision=1; n.phase=OBD_HMI_STARTING;
    for(unsigned i=0;i<3;i++) if(a->read(a->context,i,&n.saved[i],&n.observed[i]) || !n.observed[i]) return -1;
    memcpy(n.desired,desired,sizeof(n.desired)); memcpy(n.owned,n.saved,sizeof(n.owned)); *s=n; return 0;
}
int obd_hmi_end(obd_hmi_state *s,uint64_t epoch,uint32_t revision,const obd_hmi_api *a) {
    if(fence(s,epoch,revision,a)) return -1;
    s->phase=OBD_HMI_RESTORING; s->revision++; return 0;
}
int obd_hmi_step(obd_hmi_state *s,uint64_t epoch,uint32_t revision,uint64_t now,const obd_hmi_api *a) {
    if(fence(s,epoch,revision,a) || !now) return -1;
    s->revision++;
    if(now>=s->lease_until) s->phase=OBD_HMI_RESTORING;
    /* Before further writes, read ALL owned fields. A driver/manual/OEM change
     * yields that field rather than restoring a stale snapshot over it. */
    for(unsigned i=0;i<3;i++) {
        int actual; uint64_t rev;
        if(a->read(a->context,i,&actual,&rev) || !rev || rev<s->observed[i]) return failure(s,a);
        unsigned bit=1u<<i;
        if(s->waiting&bit) {
            int wanted=s->phase==OBD_HMI_RESTORING && (s->requested&bit)?s->saved[i]:s->desired[i];
            if(actual==wanted && rev==s->receipt[i]) {
                s->owned[i]=actual; s->observed[i]=rev; s->waiting&=~bit;
                if(s->requested&bit) { s->changed&=~bit; s->requested&=~bit; }
                else s->changed|=bit;
            } else if(actual!=s->owned[i] || rev>s->observed[i]) {
                s->yielded|=bit; s->waiting&=~bit; s->changed&=~bit; s->requested&=~bit; s->phase=OBD_HMI_RESTORING;
            }
        } else if(actual!=s->owned[i] || rev!=s->observed[i]) {
            s->yielded|=bit; s->changed&=~bit; s->phase=OBD_HMI_RESTORING;
        }
    }
    if(s->waiting) return now>=s->lease_until?failure(s,a):0;
    if(s->phase==OBD_HMI_RESTORING) {
        for(int i=2;i>=0;i--) if(s->changed&(1u<<(unsigned)i)) {
            s->receipt[i]=0;
            if(a->write(a->context,(unsigned)i,s->owned[i],s->observed[i],s->saved[i],&s->receipt[i]) ||
               s->receipt[i]<=s->observed[i]) return failure(s,a);
            s->waiting|=1u<<(unsigned)i; s->requested|=1u<<(unsigned)i; return 0;
        }
        if(a->video_stop(a->context)) { s->phase=OBD_HMI_RECOVERY; return -1; }
        s->phase=OBD_HMI_IDLE; return 0;
    }
    if(a->video_ready(a->context)) return 0; /* Wait for independently observed video. */
    for(unsigned i=0;i<3;i++) if(!(s->changed&(1u<<i)) && s->owned[i]!=s->desired[i]) {
        s->receipt[i]=0;
        if(a->write(a->context,i,s->owned[i],s->observed[i],s->desired[i],&s->receipt[i]) ||
           s->receipt[i]<=s->observed[i]) return failure(s,a);
        s->waiting|=1u<<i; return 0;
    }
    s->phase=OBD_HMI_PLAYING; return 0;
}
