#include "trial.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"%s:%d: %s\n",__FILE__,__LINE__,#x); exit(1); } } while(0)
typedef struct { obd_trial_state disk; int commit_failure, corrupt, unhealthy, writes; } fixture;
static int verify(void *opaque,const uint8_t identity[32],const obd_trial_slot *s) {
    fixture *f=opaque;
    return memcmp(identity,f->disk.identity_sha256,32) || (f->corrupt && s->package_id[0]=='b');
}
static int commit(void *opaque,uint32_t previous,const obd_trial_state *next) {
    fixture *f=opaque;
    CHECK(next->revision==previous+1);
    if(previous!=f->disk.revision || f->commit_failure==1) return -1;
    f->disk=*next; f->writes++;
    return f->commit_failure==2?-1:0; /* Commit happened, receipt lost. */
}
static int health(void *opaque,const obd_trial_slot *s) {
    fixture *f=opaque; CHECK(s->present); return f->unhealthy;
}
static void init(fixture *f,obd_trial_state *s,obd_trial_api *a) {
    memset(f,0,sizeof(*f)); memset(s,0,sizeof(*s));
    s->format=1; s->revision=1; s->identity_sha256[0]=1; f->disk=*s;
    *a=(obd_trial_api){f,verify,commit,health};
}
static obd_trial_slot release(char id,unsigned patch) {
    obd_trial_slot s; memset(&s,0,sizeof(s)); s.present=1;
    memset(s.package_id,id,32); s.version[2]=(uint16_t)patch; s.tree_sha256[0]=1;
    return s;
}
int main(void) {
    fixture f; obd_trial_state s; obd_trial_api a; obd_trial_slot selected;
    const uint8_t nonce[16]={1}; obd_trial_slot first=release('a',0), second=release('b',1);
    init(&f,&s,&a);
    CHECK(!obd_trial_validate(&s));
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_OEM && !selected.present);
    CHECK(!obd_trial_activate(&s,&first,nonce,&a)); /* First 0.0.0 is valid. */
    CHECK(obd_trial_confirm(&s,first.package_id,nonce,&a)==OBD_TRIAL_INVALID);
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_CANDIDATE && s.attempted);
    CHECK(!obd_trial_confirm(&s,first.package_id,nonce,&a));
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_CURRENT);
    CHECK(obd_trial_activate(&s,&first,nonce,&a)==OBD_TRIAL_INVALID);
    CHECK(!obd_trial_activate(&s,&second,nonce,&a));
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_CANDIDATE);
    f.unhealthy=1;
    CHECK(obd_trial_confirm(&s,second.package_id,nonce,&a)==OBD_TRIAL_HEALTH);
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_CURRENT && selected.package_id[0]=='a');
    CHECK(s.highwater[2]==1 && !s.candidate.present);
    CHECK(obd_trial_activate(&s,&second,nonce,&a)==OBD_TRIAL_INVALID);

    /* Storage failures and lost commit receipts must never return a candidate. */
    for(int fail=1;fail<=2;fail++) {
        init(&f,&s,&a); CHECK(!obd_trial_activate(&s,&second,nonce,&a));
        f.commit_failure=fail;
        CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_COMMIT && !selected.present);
        if(fail==2) { /* Reload durable state: trial was consumed, never retried. */
            s=f.disk; f.commit_failure=0;
            CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_OEM && !selected.present);
        }
    }
    init(&f,&s,&a); CHECK(!obd_trial_activate(&s,&second,nonce,&a));
    f.corrupt=1;
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_OEM && !selected.present);
    CHECK(!s.candidate.present && s.highwater[2]==1);
    CHECK(obd_trial_activate(&s,&second,nonce,&a)==OBD_TRIAL_INVALID);

    init(&f,&s,&a); f.corrupt=1;
    CHECK(obd_trial_activate(&s,&second,nonce,&a)==OBD_TRIAL_VERIFY && f.writes==0);
    init(&f,&s,&a); CHECK(!obd_trial_activate(&s,&second,nonce,&a));
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_CANDIDATE);
    uint8_t wrong[16]={2};
    CHECK(obd_trial_confirm(&s,second.package_id,wrong,&a)==OBD_TRIAL_INVALID);
    CHECK(obd_trial_confirm(&s,first.package_id,nonce,&a)==OBD_TRIAL_INVALID);
    f.corrupt=1;
    CHECK(obd_trial_confirm(&s,second.package_id,nonce,&a)==OBD_TRIAL_VERIFY);

    init(&f,&s,&a); s.revision=UINT32_MAX; f.disk=s;
    CHECK(obd_trial_activate(&s,&first,nonce,&a)==OBD_TRIAL_INVALID && !f.writes);
    init(&f,&s,&a); s.attempted=1;
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_INVALID && !selected.present && !f.writes);
    init(&f,&s,&a); s.identity_sha256[0]=0;
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_INVALID);
    init(&f,&s,&a); s.current.present=1;
    CHECK(obd_trial_boot(&s,&selected,&a)==OBD_TRIAL_INVALID);
    puts("Native trial policy: failure, rollback, highwater and health gates passed; host callbacks only.");
    return 0;
}
