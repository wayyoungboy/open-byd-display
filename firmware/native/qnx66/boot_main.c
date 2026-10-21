#ifdef OBD_IO_HOST
#define _POSIX_C_SOURCE 200809L
#ifdef __APPLE__
#define _DARWIN_C_SOURCE
#endif
#include <unistd.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/wait.h>
#include <signal.h>
#include <stdlib.h>
#include <stdio.h>
#else
extern int pipe(int [2]);
extern int fork(void);
extern int execve(const char *,char *const [],char *const []);
extern int close(int);
extern int dup2(int,int);
extern int fcntl(int,int,...);
extern int open(const char *,int,...);
extern int flock(int,int);
extern int unlink(const char *);
extern int kill(int,int);
extern void _exit(int) __attribute__((noreturn));
extern void free(void *);
extern void (*signal(int,void (*)(int)))(int);
enum { O_RDWR=2,O_CREAT=0x100,O_NONBLOCK=0x80,LOCK_EX=2,LOCK_NB=4,F_DUPFD=0 };
#endif
#include "store.h"
#include "disk.h"
#include "profile.h"
#include "io.h"
#include "process_wait.h"
#include "sha256.h"
#include "baseline_platform.h"
#include <string.h>
extern int snprintf(char *,size_t,const char *,...);
static volatile int stop_requested;
static void stop(int sig) { if(sig!=13) stop_requested=1; }
static unsigned u32(const uint8_t *p) { return (unsigned)p[0]|(unsigned)p[1]<<8|(unsigned)p[2]<<16|(unsigned)p[3]<<24; }
static int open_store(obd_store *s,const char *root,const char *target,const uint8_t baseline[32],const uint8_t key[32]) {
    obd_io_api io; obd_io_platform(&io); uint64_t start=io.now(); if(!start) return -1;
    for(;;) {
        int rc=obd_store_open(s,root,target,baseline,key);
        if(rc!=OBD_STORE_BUSY) return rc;
        uint64_t now=io.now();
        if(stop_requested || !now || now<start || now-start>=2000 || io.poll(0,0,25)<0) return -1;
    }
}
typedef struct {
    int pid,lease,health; unsigned count,submitted,posts,ready;
    uint8_t report[24]; uint64_t last_report,last_progress,stable_since;
    obd_io_api io;
} child_state;
static int child_health(void *context,const obd_trial_slot *slot) {
    child_state *c=context; (void)slot;
    uint64_t now=c->io.now();
#ifdef OBD_SUPERVISOR_TEST
    const unsigned stable=500;
#else
    const unsigned stable=5000;
#endif
    /* Platform health means owned live process + fresh decoded POST/property
     * evidence. It deliberately does not assert that a driver saw pixels. */
    return !now || !c->ready || c->submitted<3 || c->posts<3 || !c->stable_since || now<c->stable_since ||
        now-c->stable_since<stable || now<c->last_report || now-c->last_report>=1000 ||
        now<c->last_progress || now-c->last_progress>=1500 || kill(c->pid,0)?-1:0;
}
static int collect(child_state *c) {
    for(unsigned i=0;i<16;i++) {
        obd_pollfd p={c->health,1,0}; int n=c->io.poll(&p,1,0);
        if(n<0 || (p.revents&0x28)) return -1;
        if(!n) return 0;
        int got=c->io.read(c->health,c->report+c->count,24-c->count);
        if(got<=0 || (unsigned)got>24-c->count) return -1;
        c->count+=(unsigned)got;
        if(c->count!=24) continue;
        c->count=0;
        if(memcmp(c->report,"OBDH01\n\0",8) || u32(c->report+8)>1 || u32(c->report+20)) return -1;
        unsigned ready=u32(c->report+8),frames=u32(c->report+12),posts=u32(c->report+16);
        uint64_t now=c->io.now(); if(!now) return -1;
        if(!ready || frames<c->submitted || posts<c->posts ||
           (c->last_progress && now-c->last_progress>=1500)) c->stable_since=0;
        if(ready && frames>=3 && posts>=3 && !c->stable_since) c->stable_since=now;
        if(frames!=c->submitted && posts!=c->posts) c->last_progress=now;
        c->ready=ready; c->submitted=frames; c->posts=posts; c->last_report=now;
    }
    return 0;
}
static int spawn(child_state *c,const char *directory,const obd_service_profile *p,int lifetime_lock) {
    int lease[2],health[2]; if(pipe(lease)) return -1;
    if(pipe(health)) { close(lease[0]); close(lease[1]); return -1; }
    char executable[384],values[8][16];
    unsigned numbers[8]={p->display,p->physical_width,p->physical_height,p->x,p->y,p->width,p->height,p->zorder};
    if(obd_disk_join(executable,directory,"service.elf")) goto fail;
    for(unsigned i=0;i<8;i++) if(snprintf(values[i],16,"%u",numbers[i])<=0) goto fail;
    char *argv[]={executable,"--baseline-serve",(char *)p->address,"47792","OBD",
        values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],"--supervised","3","4",0};
    /* No inherited SOCK, loader search path, credentials or shell command. */
    char *env[]={"PATH=/proc/boot:/bin:/sbin:/usr/bin:/usr/sbin","LANG=C",
        p->socket_environment[0]?(char *)p->socket_environment:0,0};
    c->pid=fork(); if(c->pid<0) goto fail;
    if(!c->pid) {
        int a=fcntl(lease[0],F_DUPFD,10),b=fcntl(health[1],F_DUPFD,10),held=fcntl(lifetime_lock,F_DUPFD,10);
        if(a<0 || b<0 || held<0 || dup2(a,3)!=3 || dup2(b,4)!=4 || dup2(held,5)!=5) _exit(126);
        /* Bootstrap starts with standard streams. All nonstandard inherited
         * descriptors except the lease, health and shared lifetime lock are
         * closed. If the parent dies, child retains the lock until lease exit. */
        for(int fd=6;fd<65536;fd++) close(fd);
        if(execve(executable,argv,env)) _exit(127);
        _exit(127);
    }
    close(lease[0]); close(health[1]); c->lease=lease[1]; c->health=health[0];
    int a,b;
    if(obd_io_nonblock(c->lease,&a) || obd_io_nonblock(c->health,&b)) return -1;
    return 0;
fail:
    close(lease[0]); close(lease[1]); close(health[0]); close(health[1]); return -1;
}
static int reap(child_state *c) {
    if(c->lease>=0) { close(c->lease); c->lease=-1; }
    if(c->health>=0) { close(c->health); c->health=-1; }
    if(c->pid<=0) return 0;
    (void)kill(c->pid,15);
    uint64_t begin=c->io.now(); int status;
    for(unsigned i=0;i<40;i++) {
        int n=obd_child_poll(c->pid,&status);
        if(n==c->pid) { c->pid=0; return 0; }
        if(n<0) { if(c->io.error()==4) continue; return -1; }
        if(!begin || c->io.now()-begin>=2000) break;
        (void)c->io.poll(0,0,50);
    }
    (void)kill(c->pid,9);
    /* Bounded: if a target cannot reap its own child, retain failure and do
     * not start another generation or perform any USB/Host recovery. */
    for(unsigned i=0;i<40;i++) {
        int n=obd_child_poll(c->pid,&status);
        if(n==c->pid) { c->pid=0; return 0; }
        if(n<0) { if(c->io.error()==4) continue; return -1; }
        (void)c->io.poll(0,0,50);
    }
    return -1;
}
static int supervise(const char *root,const char *target,const uint8_t baseline[32],const uint8_t key[32],int watch) {
    /* Different lock from the short store transaction, so stage/status remain
     * available while a confirmed generation is serving. */
    char path[384];
    if(obd_disk_private(root,1,0700,0) || obd_disk_join(path,root,"supervisor.lock")) return 78;
    int exists=obd_disk_exists(path);
    if(exists<0 || (exists && obd_disk_private(path,0,0600,0))) return 78;
    int lock=open(path,O_RDWR|O_CREAT|O_NONBLOCK,0600); obd_file_metadata a,b;
    if(lock<0) return 78;
    if(obd_disk_metadata(0,lock,&a) || obd_disk_private(path,0,0600,&b) || a.inode!=b.inode || a.device!=b.device || a.size || flock(lock,LOCK_EX|LOCK_NB)) { close(lock); return 75; }
    if(obd_disk_join(path,root,"supervisor.uncertain") || obd_disk_exists(path)!=0) { close(lock); return 78; }
    int result=0;
    for(unsigned attempt=0;(watch || attempt<2) && !stop_requested;attempt++) {
        obd_store s; obd_trial_slot selected; char directory[384]; uint8_t raw[64],nonce[16]; obd_service_profile profile;
        if(open_store(&s,root,target,baseline,key)) { result=78; break; }
        int choice=obd_store_select(&s,&selected,directory);
        memcpy(nonce,s.state.nonce,16);
        int invalid=choice<0 || (choice!=OBD_TRIAL_OEM && (obd_store_current_profile(&s,&selected,raw) || obd_profile_decode(&profile,raw,target) || !profile.enabled));
        obd_store_close(&s);
        if(choice==OBD_TRIAL_OEM) {
            if(!watch) break; /* Original SLM is already independent. */
            obd_io_api io; obd_io_platform(&io);
            if(io.poll(0,0,250)<0 && !stop_requested) { result=71; break; }
            continue;
        }
        if(invalid) { result=78; if(choice==OBD_TRIAL_CANDIDATE) continue; break; }
        if(watch && choice==OBD_TRIAL_CURRENT) result=0;
        child_state child; memset(&child,0,sizeof(child)); child.lease=child.health=-1; obd_io_platform(&child.io);
        uint64_t started=child.io.now(),pulse=0;
        int confirmed=choice==OBD_TRIAL_CURRENT,failed=0,reload=0;
        uint64_t checked_update=0;
#ifdef OBD_SUPERVISOR_TEST
        unsigned trial_ms=2500;
#else
        unsigned trial_ms=profile.trial_seconds*1000;
#endif
        /* Persist the in-flight barrier BEFORE fork. Unexpected supervisor
         * death cannot make an unconfirmed cleanup look like a clean boot.
         * Clearing after abnormal death requires independent operator checks. */
        static const uint8_t in_flight[]="OWN_CHILD_IN_FLIGHT_OR_CLEANUP_UNCONFIRMED\n";
        if(obd_disk_join(path,root,"supervisor.uncertain") || obd_disk_create(path,in_flight,sizeof(in_flight)-1,0600) || obd_disk_sync(root)) { result=75; break; }
        if(!started || spawn(&child,directory,&profile,lock)) failed=1;
        while(!failed && !stop_requested) {
            int status=0; int waited=obd_child_poll(child.pid,&status);
            if(waited==child.pid) { child.pid=0; failed=1; break; }
            if(waited<0) { if(child.io.error()==4) continue; failed=1; break; }
            uint64_t now=child.io.now(); if(!now || now<started) { failed=1; break; }
            if(!pulse || now-pulse>=250) {
                uint8_t heartbeat=0xa7;
                if(child.io.write(child.lease,&heartbeat,1)!=1) { failed=1; break; }
                pulse=now;
            }
            if(collect(&child)) { failed=1; break; }
            if(!confirmed && !child_health(&child,&selected)) {
                /* Never block the live child's lease/health loop on a store
                 * writer. BUSY retries on the next ordinary watchdog tick. */
                int opened=obd_store_open(&s,root,target,baseline,key);
                if(opened && opened!=OBD_STORE_BUSY) { failed=1; break; }
                if(!opened) {
                    int rc=collect(&child)?OBD_TRIAL_HEALTH:obd_store_confirm(&s,selected.package_id,nonce,child_health,&child);
                    obd_store_close(&s);
                    if(rc && rc!=OBD_TRIAL_HEALTH) { failed=1; break; }
                    if(!rc) { confirmed=1; if(watch) result=0; }
                }
            }
            if(!confirmed && now-started>=trial_ms) { failed=1; break; }
            if(watch && confirmed && (!checked_update || now-checked_update>=250)) {
                checked_update=now;
                int opened=obd_store_open(&s,root,target,baseline,key);
                if(opened && opened!=OBD_STORE_BUSY) { failed=1; break; }
                if(!opened) {
                    reload=s.state.candidate.present && !s.state.attempted &&
                        strcmp(s.state.candidate.package_id,selected.package_id);
                    obd_store_close(&s);
                    if(reload) break;
                }
            }
            if(child.io.poll(0,0,50)<0 && !stop_requested) { failed=1; break; }
        }
        int cleaned=reap(&child);
        if(cleaned) { result=75; break; } /* Keep the already durable barrier. */
        if(obd_disk_join(path,root,"supervisor.uncertain") || obd_disk_private(path,0,0600,0) || unlink(path) || obd_disk_sync(root)) { result=75; break; }
        if(stop_requested) break;
        if(reload) { result=0; continue; } /* Old owned child is reaped before select/exec. */
        if(failed) result=70;
        if(confirmed || choice!=OBD_TRIAL_CANDIDATE) break;
        /* Next select durably rolls an unconfirmed trial back before handing
         * off to a verified old current. No retry storm or OEM PID signaling. */
    }
    close(lock); return result;
}
int main(int argc,char **argv) {
    if((argc!=11 && argc!=12) || (argc==12 && strcmp(argv[11],"--watch")) || strcmp(argv[1],"--root") || strcmp(argv[3],"--key") || strcmp(argv[5],"--key-sha256") ||
       strcmp(argv[7],"--target") || strcmp(argv[9],"--baseline")) return 64;
    uint8_t baseline[32],pin[32],actual[32],*key=0; size_t bytes=0;
    if(obd_generation_unhex(baseline,argv[10],32) || obd_generation_unhex(pin,argv[6],32) ||
       obd_disk_read(argv[4],&key,&bytes,32,0) || bytes!=32) { free(key); return 65; }
    obd_sha256 hash; obd_sha256_init(&hash); obd_sha256_update(&hash,key,32); obd_sha256_final(&hash,actual);
    if(memcmp(actual,pin,32)) { free(key); return 65; }
#ifndef OBD_IO_HOST
    uint8_t compiled[32];
    if(strcmp(argv[8],obd_baseline_target()) || obd_generation_unhex(compiled,obd_baseline_zip(),32) ||
       memcmp(compiled,baseline,32) || obd_baseline_platform_check(0)) { free(key); return 78; }
#endif
    if(signal(2,stop)==(void (*)(int))-1 || signal(15,stop)==(void (*)(int))-1 || signal(13,stop)==(void (*)(int))-1) { free(key); return 71; }
    int result=supervise(argv[2],argv[8],baseline,key,argc==12); free(key); return result;
}
