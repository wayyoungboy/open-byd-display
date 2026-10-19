#include "usb_txn.h"
#include "hmi_txn.h"
#include "usb_plan.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"backend line %d: %s\n",__LINE__,#x); exit(1); } } while(0)
typedef struct {
    obd_usb_state disk; unsigned actions[64],count,fail_action,fail_probe,fail_commit,cancel,cancel_at_barrier;
    int values[3],pending_value; unsigned pending_field,writes,stops; uint64_t rev[3]; int pending,video;
} fixture;
static int usb_probe(void *v,unsigned p) { fixture *f=v; return p==f->fail_probe?-1:0; }
static int usb_command(void *v,unsigned action) {
    fixture *f=v; CHECK(f->count<64); f->actions[f->count++]=action;
    if(action==OBD_USB_LINK_UP) CHECK(f->disk.peer_possible && f->disk.phase==OBD_USB_SWITCHING);
    return action==f->fail_action?-1:0;
}
static int commit(void *v,uint32_t revision,const obd_usb_state *s) {
    fixture *f=v; CHECK(revision==f->disk.revision);
    if(++f->fail_commit==100) return -1;
    f->disk=*s; if(f->cancel_at_barrier && s->peer_possible) f->cancel=1; return 0;
}
static int cancelled(void *v) { return ((fixture *)v)->cancel; }
static void init(fixture *f,obd_usb_state *s) {
    memset(f,0,sizeof(*f)); memset(s,0,sizeof(*s)); s->revision=1; s->phase=OBD_USB_HOST; s->owner[0]=1; f->disk=*s;
}
static void usb_tests(void) {
    fixture f; obd_usb_state s; uint8_t owner[16]={1},request[16]={2},fresh[16]={3};
    obd_usb_api a={&f,usb_probe,usb_command,commit,cancelled};
    init(&f,&s); CHECK(!obd_usb_to_ncm(&s,owner,request,&a)); CHECK(s.phase==OBD_USB_NCM && s.peer_possible);
    CHECK(obd_usb_to_host(&s,owner,request,&a)); CHECK(!obd_usb_to_host(&s,owner,fresh,&a)); CHECK(s.phase==OBD_USB_HOST);
    for(unsigned action=OBD_USB_UNLOAD;action<=OBD_USB_LINK_UP;action++) {
        init(&f,&s); f.fail_action=action; CHECK(obd_usb_to_ncm(&s,owner,request,&a));
        if(action==OBD_USB_UNLOAD || action==OBD_USB_LINK_UP) CHECK(s.phase==OBD_USB_UNCERTAIN);
        else CHECK(s.phase==OBD_USB_HOST && !s.peer_possible);
        if(action==OBD_USB_LINK_UP) {
            CHECK(f.actions[f.count-1]==OBD_USB_LINK_UP && s.peer_possible);
            CHECK(obd_usb_to_host(&s,owner,fresh,&a)); /* Uncertain cannot be cleared by a new button request. */
        }
    }
    init(&f,&s); f.fail_probe=OBD_USB_PHYSICALLY_DISCONNECTED;
    CHECK(!obd_usb_to_ncm(&s,owner,request,&a)); unsigned before=f.count;
    CHECK(obd_usb_to_host(&s,owner,fresh,&a)); CHECK(before==f.count);
    init(&f,&s); f.fail_probe=OBD_USB_HOST_EMPTY; CHECK(obd_usb_to_ncm(&s,owner,request,&a)); CHECK(f.count==1);
    init(&f,&s); f.fail_commit=99; CHECK(obd_usb_to_ncm(&s,owner,request,&a)); CHECK(s.poisoned && !f.count);
    init(&f,&s); s.phase=f.disk.phase=OBD_USB_SWITCHING;
    CHECK(!obd_usb_interrupted(&s,&a)); CHECK(s.phase==OBD_USB_UNCERTAIN && !f.count);
    init(&f,&s); owner[0]=9; CHECK(obd_usb_to_ncm(&s,owner,request,&a)); CHECK(!f.count);
    init(&f,&s); owner[0]=1; f.cancel_at_barrier=1;
    CHECK(obd_usb_to_ncm(&s,owner,request,&a)); CHECK(s.phase==OBD_USB_UNCERTAIN && s.peer_possible);
    for(unsigned i=0;i<f.count;i++) CHECK(f.actions[i]!=OBD_USB_LINK_UP);
}
static int hread(void *v,unsigned field,int *value,uint64_t *rev) { fixture *f=v; *value=f->values[field]; *rev=f->rev[field]; return 0; }
static int hwrite(void *v,unsigned field,int expected,uint64_t rev,int desired,uint64_t *receipt) {
    fixture *f=v; CHECK(!f->pending && expected==f->values[field] && rev==f->rev[field]);
    f->writes++; f->pending=1; f->pending_field=field; f->pending_value=desired; *receipt=rev+1; return 0;
}
static int ready(void *v) { return ((fixture *)v)->video?0:-1; }
static int hstop(void *v) { ((fixture *)v)->stops++; return 0; }
static void acknowledge(fixture *f) { if(f->pending) { f->values[f->pending_field]=f->pending_value; f->rev[f->pending_field]++; f->pending=0; } }
static void step(fixture *f,obd_hmi_state *s,const obd_hmi_api *a,uint64_t now) { CHECK(!obd_hmi_step(s,s->epoch,s->revision,now,a)); (void)f; }
static void hmi_tests(void) {
    fixture f; memset(&f,0,sizeof(f)); f.rev[0]=f.rev[1]=f.rev[2]=1;
    obd_hmi_state s; memset(&s,0,sizeof(s)); int desired[3]={1,2,3}; obd_hmi_api a={&f,hread,hwrite,ready,hstop};
    CHECK(!obd_hmi_begin(&s,17,100,20000,desired,&a));
    step(&f,&s,&a,101); CHECK(!f.writes && s.phase==OBD_HMI_STARTING);
    f.video=1; step(&f,&s,&a,102); CHECK(f.writes==1 && s.phase==OBD_HMI_STARTING);
    step(&f,&s,&a,103); CHECK(f.writes==1 && s.waiting); /* ACK alone is not observed feedback. */
    CHECK(obd_hmi_step(&s,18,s.revision,104,&a));
    CHECK(obd_hmi_step(&s,17,s.revision-1,104,&a));
    for(unsigned i=0;i<3;i++) { acknowledge(&f); step(&f,&s,&a,110+i); }
    CHECK(s.phase==OBD_HMI_PLAYING && s.changed==7);
    f.values[1]=9; f.rev[1]++; /* User takes the center field. */
    step(&f,&s,&a,120); CHECK(s.phase==OBD_HMI_RESTORING && (s.yielded&2));
    for(unsigned i=0;i<3;i++) { acknowledge(&f); step(&f,&s,&a,130+i); if(s.phase==OBD_HMI_IDLE) break; }
    CHECK(s.phase==OBD_HMI_IDLE && f.stops==1 && f.values[0]==0 && f.values[1]==9 && f.values[2]==0);
    CHECK(f.writes==5); /* Three changes, two restores; manual field untouched. */
    CHECK(obd_hmi_begin(&s,17,1000,10000,desired,&a));
    CHECK(!obd_hmi_begin(&s,18,1000,10000,desired,&a));
    CHECK(obd_hmi_end(&s,17,1,&a));
    step(&f,&s,&a,1001); /* Pending write never gets feedback. */
    CHECK(obd_hmi_step(&s,18,s.revision,11000,&a));
    CHECK(s.phase==OBD_HMI_RECOVERY && f.stops==2);
    memset(&s,0,sizeof(s)); memset(&f,0,sizeof(f)); f.rev[0]=f.rev[1]=f.rev[2]=1; f.video=1;
    CHECK(!obd_hmi_begin(&s,17,100,10000,desired,&a)); step(&f,&s,&a,101);
    f.pending=0; f.values[0]=desired[0]; f.rev[0]=3; /* Same value, different writer/receipt. */
    step(&f,&s,&a,102);
    CHECK(s.phase==OBD_HMI_IDLE && s.yielded==1 && !s.changed && f.writes==1 && f.values[0]==1);
}
static void plan_tests(void) {
    const char *targets[]={"song-plus-dmi-2021","tang-plus-dmi-2021"};
    for(unsigned i=0;i<2;i++) {
        char root[112]; snprintf(root,sizeof(root),"/fs/tmpfs/OpenBYDDisplay/%s/0123456789abcdef0123456789abcdef",targets[i]);
        obd_usb_command plan;
        CHECK(!obd_usb_plan(&plan,targets[i],root,OBD_USB_START_DCD));
        CHECK(plan.argc==6 && !strcmp(plan.argv[5],"ioport=0x488d0000,irq=110,linkdown") && !plan.sock[0]);
        CHECK(!obd_usb_plan(&plan,targets[i],root,OBD_USB_ADDRESS)); CHECK(!strncmp(plan.sock,"SOCK=/fs/tmpfs/OpenBYDDisplay/",28));
        CHECK(!obd_usb_plan(&plan,targets[i],root,OBD_USB_LINK_UP)); CHECK(!strcmp(plan.argv[6],"1"));
        CHECK(obd_usb_plan(&plan,targets[i],root,OBD_USB_STOP_DCD));
        CHECK(obd_usb_plan(&plan,targets[1-i],root,OBD_USB_START_NET));
    }
    obd_usb_command plan;
    CHECK(obd_usb_plan(&plan,targets[0],"/fs/tmpfs/OpenBYDDisplay/song-plus-dmi-2021/../arbitrary",OBD_USB_UNLOAD));
}
int main(void) { usb_tests(); hmi_tests(); plan_tests(); puts("USB transaction/peer barrier and HMI fenced feedback/restore doubles passed"); return 0; }
