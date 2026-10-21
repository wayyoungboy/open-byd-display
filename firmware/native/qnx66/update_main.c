#ifdef OBD_IO_HOST
#define _POSIX_C_SOURCE 200809L
#ifdef __APPLE__
#define _DARWIN_C_SOURCE
#endif
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <signal.h>
#include <errno.h>
#else
extern int open(const char *,int,...);
extern int close(int);
extern int flock(int,int);
extern int rename(const char *,const char *);
extern int unlink(const char *);
extern unsigned umask(unsigned);
extern void *malloc(unsigned);
extern void free(void *);
extern char *getenv(const char *);
extern void (*signal(int,void (*)(int)))(int);
enum { O_RDWR=2,O_CREAT=0x100,O_NONBLOCK=0x80,LOCK_EX=2,LOCK_NB=4 };
#endif
#include "store.h"
#include "disk.h"
#include "io.h"
#include "network.h"
#include "profile.h"
#include "sha256.h"
#include "update_request.h"
#include "baseline_platform.h"
#include <string.h>
extern int snprintf(char *,size_t,const char *,...);
enum { NONE,RECEIVING,READY,APPLYING,AWAITING,APPLIED,CANCELLED,FAILED,RECOVERY };
enum { E_NONE,E_TRANSFER,E_PACKAGE,E_ROLLBACK,E_UNCERTAIN,E_PROFILE,E_BUSY };
typedef struct {
    unsigned phase,total,received,enabled,error;
    char id[33],package[33]; uint8_t digest[32];
} job;
typedef struct {
    const char *root,*target;
    uint8_t key[32],baseline[32],identity[32];
    job task; int poisoned;
} service;
static volatile int stopping;
static void stop(int sig) { if(sig!=13) stopping=1; }
static void hash(const uint8_t *data,size_t n,uint8_t out[32]) {
    obd_sha256 h; obd_sha256_init(&h); obd_sha256_update(&h,data,n); obd_sha256_final(&h,out);
}
static unsigned u32(const uint8_t *p) { return (unsigned)p[0]|(unsigned)p[1]<<8|(unsigned)p[2]<<16|(unsigned)p[3]<<24; }
static void put32(uint8_t *p,unsigned n) { for(unsigned i=0;i<4;i++) p[i]=(uint8_t)(n>>(i*8)); }
static int nonzero(const uint8_t *p,unsigned n) { unsigned v=0; for(unsigned i=0;i<n;i++) v|=p[i]; return !!v; }
static int open_store(service *s,obd_store *store) { return obd_store_open(store,s->root,s->target,s->baseline,s->key); }
static int save(service *s) {
    uint8_t raw[256]={0}; char file[384],next[384];
    if(s->poisoned || !s->task.phase || obd_disk_join(file,s->root,"update-task.bin") || obd_disk_join(next,s->root,"update-task.next")) return -1;
    memcpy(raw,"OBDJOB1\n",8); put32(raw+8,s->task.phase); put32(raw+12,s->task.error);
    if(obd_generation_unhex(raw+16,s->task.id,16)) return -1;
    if(s->task.package[0] && obd_generation_unhex(raw+32,s->task.package,16)) return -1;
    memcpy(raw+48,s->task.digest,32); put32(raw+80,s->task.total); put32(raw+84,s->task.received); put32(raw+88,s->task.enabled);
    memcpy(raw+96,s->baseline,32); memcpy(raw+128,s->target,strlen(s->target)); memcpy(raw+160,s->identity,32);
    hash(raw,224,raw+224);
    s->poisoned=1;
    if(obd_disk_create(next,raw,sizeof(raw),0600)) return -1;
    obd_disk_checkpoint("update-task-file-synced");
    if(rename(next,file)) return -1;
    obd_disk_checkpoint("update-task-renamed");
    if(obd_disk_sync(s->root)) return -1;
    s->poisoned=0; return 0;
}
static int load(service *s) {
    char file[384],next[384]; uint8_t *raw=0,actual[32]; size_t n=0;
    if(obd_disk_join(file,s->root,"update-task.bin") || obd_disk_join(next,s->root,"update-task.next")) return -1;
    int exists=obd_disk_exists(file),temporary=obd_disk_exists(next);
    if(exists<0 || temporary<0 || (!exists && temporary)) return -1;
    if(!exists) return 0;
    if(obd_disk_read(file,&raw,&n,256,1) || n!=256) { free(raw); return -1; }
    hash(raw,224,actual); uint8_t canonical[32]={0}; memcpy(canonical,s->target,strlen(s->target));
    int bad=memcmp(raw,"OBDJOB1\n",8) || memcmp(actual,raw+224,32) || memcmp(raw+96,s->baseline,32) ||
        memcmp(raw+128,canonical,32) || memcmp(raw+160,s->identity,32) || !nonzero(raw+16,16) ||
        !nonzero(raw+48,32) || u32(raw+8)<RECEIVING || u32(raw+8)>RECOVERY || u32(raw+12)>E_BUSY ||
        !u32(raw+80) || u32(raw+80)>OBD_GEN_HEADER+OBD_GEN_FILES*OBD_GEN_ROW+OBD_GEN_PAYLOAD ||
        u32(raw+84)>u32(raw+80) || u32(raw+88)>1 || u32(raw+92) || nonzero(raw+192,32);
    if(!bad) {
        s->task.phase=u32(raw+8); s->task.error=u32(raw+12); s->task.total=u32(raw+80); s->task.received=u32(raw+84); s->task.enabled=u32(raw+88);
        memcpy(s->task.digest,raw+48,32);
        obd_generation_hex(s->task.id,raw+16,16);
        if(nonzero(raw+32,16)) obd_generation_hex(s->task.package,raw+32,16);
        if((s->task.phase>=READY && s->task.phase<=APPLIED) && (!s->task.package[0] || s->task.total!=s->task.received)) bad=1;
    }
    free(raw); if(bad) return -1;
    if(temporary && (obd_disk_private(next,0,0600,0) || unlink(next) || obd_disk_sync(s->root))) return -1;
    if(s->task.phase==RECEIVING) { s->task.phase=FAILED; s->task.error=E_TRANSFER; return save(s); }
    return 0;
}
static int reconcile(service *s,obd_store *store) {
    if(s->task.phase!=APPLYING && s->task.phase!=AWAITING) return 0;
    unsigned before=s->task.phase; uint8_t nonce[16];
    if(obd_generation_unhex(nonce,s->task.id,16)) return -1;
    if(store->state.current.present && !strcmp(store->state.current.package_id,s->task.package) &&
       !obd_store_verify_slot(store,store->identity,&store->state.current)) { s->task.phase=APPLIED; s->task.error=E_NONE; }
    else if(store->state.candidate.present && !strcmp(store->state.candidate.package_id,s->task.package) &&
            !memcmp(nonce,store->state.nonce,16)) s->task.phase=AWAITING;
    else if(before==AWAITING && !store->state.candidate.present) { s->task.phase=FAILED; s->task.error=E_ROLLBACK; }
    else { s->task.phase=RECOVERY; s->task.error=E_UNCERTAIN; }
    return before==s->task.phase?0:save(s);
}
static const char *phase(unsigned n) {
    static const char *names[]={"none","receiving","ready","applying","awaiting_confirmation","applied","cancelled","failed","recovery_required"};
    return n<=RECOVERY?names[n]:"recovery_required";
}
static const char *error_text(unsigned n) {
    static const char *names[]={"","transfer_interrupted","package_validation_failed","trial_rolled_back","activation_result_uncertain","profile_disabled_or_network_changed","store_busy"};
    return n<=E_BUSY?names[n]:"invalid_state";
}
static int send_line(obd_io *io,int fd,const char *text,int n) {
    if(n<=0 || n>=2048) return -1;
    io->deadline=io->api.now()+15000;
    return obd_io_exact(io,fd,(void *)text,(unsigned)n,1);
}
static int reply(service *s,obd_io *io,int fd,int ok,const char *error,int duplicate,int can_install) {
    char line[2048],digest[65],baseline[65];
    obd_generation_hex(digest,s->task.digest,32); obd_generation_hex(baseline,s->baseline,32);
    int n=snprintf(line,sizeof(line),"{\"ok\":%s,\"update_id\":\"%s\",\"kind\":\"native_generation\",\"stage\":\"%s\",\"package_id\":\"%s\",\"target\":\"%s\",\"baseline_sha256\":\"%s\",\"sha256\":\"%s\",\"received_bytes\":%u,\"total_bytes\":%u,\"worker_active\":false,\"applied\":%s,\"can_install\":%s,\"duplicate\":%s,\"error\":\"%s\",\"production_flashable\":false}\n",
        ok?"true":"false",s->task.id,phase(s->task.phase),s->task.package,s->target,baseline,digest,
        s->task.received,s->task.total,s->task.phase==APPLIED?"true":"false",can_install?"true":"false",duplicate?"true":"false",error?error:error_text(s->task.error));
    return send_line(io,fd,line,n);
}
static int failure(obd_io *io,int fd,const char *error) {
    char line[2048]; int n=snprintf(line,sizeof(line),"{\"ok\":false,\"error\":\"%s\"}\n",error);
    return send_line(io,fd,line,n);
}
static int info(service *s,obd_io *io,int fd,obd_store *store) {
    char line[2048],key[65],baseline[65]; uint8_t digest[32]; hash(s->key,32,digest);
    obd_generation_hex(key,digest,32); obd_generation_hex(baseline,s->baseline,32);
    int n=snprintf(line,sizeof(line),"{\"ok\":true,\"update_protocol\":3,\"native_generation_available\":true,\"receive_available\":true,\"firmware_apply_available\":false,\"component_apply_available\":false,\"target\":\"%s\",\"baseline_sha256\":\"%s\",\"public_key_sha256\":\"%s\",\"native_max_bytes\":%u,\"trial_active\":%s,\"update_scope\":\"own_service_generations\",\"production_flashable\":false}\n",
        s->target,baseline,key,OBD_GEN_HEADER+OBD_GEN_FILES*OBD_GEN_ROW+OBD_GEN_PAYLOAD,store->state.candidate.present?"true":"false");
    return send_line(io,fd,line,n);
}
static int can_install(service *s,obd_store *store) {
    if(s->task.phase!=READY || !s->task.enabled || store->state.candidate.present) return 0;
    obd_trial_slot slot; uint8_t profile[64];
    if(obd_store_inspect(store,s->task.package,&slot,profile)) return 0;
    if(store->state.highwater_present) {
        int newer=0;
        for(unsigned i=0;i<3;i++) if(slot.version[i]!=store->state.highwater[i]) { newer=slot.version[i]>store->state.highwater[i]; break; }
        if(!newer) return 0;
    }
    return 1;
}
static int upload(service *s,const obd_update_request *r,obd_io *io,int fd,obd_store *store) {
    uint8_t expected[32],baseline[32];
    if(strcmp(r->target,s->target) || obd_generation_unhex(baseline,r->baseline,32) || memcmp(baseline,s->baseline,32) ||
       obd_generation_unhex(expected,r->sha256,32) || !nonzero(expected,32)) return failure(io,fd,"target_or_baseline_mismatch");
    if(s->task.phase && !strcmp(s->task.id,r->id)) {
        if(s->task.total!=r->bytes || memcmp(s->task.digest,expected,32)) return failure(io,fd,"update_id_conflict");
        return reply(s,io,fd,1,0,1,can_install(s,store));
    }
    if(s->task.phase && s->task.phase!=CANCELLED && s->task.phase!=APPLIED) return failure(io,fd,"another_update_active");
    memset(&s->task,0,sizeof(s->task)); s->task.phase=RECEIVING; s->task.total=r->bytes;
    memcpy(s->task.id,r->id,33); memcpy(s->task.digest,expected,32);
    if(save(s)) return -1;
    /* Do not hold the store transaction while waiting for network bytes. */
    obd_store_close(store);
    char line[2048]; int n=snprintf(line,sizeof(line),"{\"ok\":true,\"ready\":true,\"update_id\":\"%s\"}\n",r->id);
    uint8_t *raw=malloc(r->bytes); int result=-1;
    if(raw && !send_line(io,fd,line,n)) {
        io->deadline=io->api.now()+120000;
        result=obd_io_exact(io,fd,raw,r->bytes,0);
    }
    if(result) { free(raw); s->task.phase=FAILED; s->task.error=E_TRANSFER; return save(s)?-1:reply(s,io,fd,0,0,0,0); }
    uint8_t digest[32]; hash(raw,r->bytes,digest); obd_generation generation;
    result=memcmp(digest,expected,32) || obd_generation_verify(&generation,raw,r->bytes,s->key,s->target,s->baseline);
    obd_service_profile profile; uint8_t profile_raw[64]; int found=0;
    if(!result) for(unsigned i=0;i<generation.count;i++) if(!strcmp(generation.files[i].name,"profile.dat") && generation.files[i].bytes==64) {
        memcpy(profile_raw,generation.payload+generation.files[i].offset,64); found=1;
    }
    if(!result) result=!found || obd_profile_decode(&profile,profile_raw,s->target);
    int opened=0;
    if(!result) { opened=open_store(s,store); result=opened; }
    if(!result) result=obd_store_stage(store,raw,r->bytes,s->task.package);
    free(raw); s->task.received=r->bytes;
    if(result) { s->task.phase=FAILED; s->task.error=opened==OBD_STORE_BUSY?E_BUSY:E_PACKAGE; }
    else { s->task.phase=READY; s->task.enabled=profile.enabled; }
    if(save(s)) return -1;
    return reply(s,io,fd,!result,0,0,!result && can_install(s,store));
}
static int apply(service *s,obd_io *io,int fd,obd_store *store) {
    if(s->task.phase==APPLYING || s->task.phase==AWAITING || s->task.phase==APPLIED) return reply(s,io,fd,1,0,1,0);
    if(!can_install(s,store)) return reply(s,io,fd,0,"not_installable",0,0);
    obd_trial_slot slot; uint8_t raw[64],nonce[16]; obd_service_profile profile;
    if(obd_store_inspect(store,s->task.package,&slot,raw) || obd_profile_decode(&profile,raw,s->target) || !profile.enabled) return reply(s,io,fd,0,"profile_disabled",0,0);
    /* An update must not silently move the service to a different network
     * namespace and strand the immutable receiver on its old interface. */
    const char *sock=getenv("SOCK");
    if(strcmp(profile.socket_environment[0]?profile.socket_environment+5:"",sock?sock:"")) return reply(s,io,fd,0,"network_namespace_changed",0,0);
    if(obd_generation_unhex(nonce,s->task.id,16)) return -1;
    s->task.phase=APPLYING;
    if(save(s)) return -1;
    obd_disk_checkpoint("update-before-activate");
    int rc=obd_store_activate(store,s->task.package,nonce);
    obd_disk_checkpoint("update-after-activate");
    /* Never retry an ambiguous activation, including after process death. */
    if(rc) { s->task.phase=RECOVERY; s->task.error=E_UNCERTAIN; }
    else s->task.phase=AWAITING;
    if(save(s)) return -1;
    return reply(s,io,fd,!rc,0,0,0);
}
static int connection(service *s,int fd) {
    obd_io io; obd_io_platform(&io.api); io.cancel=&stopping; io.deadline=io.api.now()+15000;
    char line[OBD_UPDATE_LINE]; unsigned n=0; int complete=0;
    while(n<sizeof(line)) {
        if(obd_io_exact(&io,fd,line+n,1,0)) return -1;
        if(line[n++]=='\n') { complete=1; break; }
    }
    obd_update_request r;
    if(!complete || obd_update_parse(&r,line,n)) return failure(&io,fd,"invalid_request");
    obd_store store; int opened=open_store(s,&store);
    if(opened) return failure(&io,fd,opened==OBD_STORE_BUSY?"store_busy":"store_unavailable");
    int result;
    if(reconcile(s,&store)) result=-1;
    else if(r.op==OBD_UPDATE_INFO) result=info(s,&io,fd,&store);
    else if(r.op==OBD_UPDATE_UPLOAD) result=upload(s,&r,&io,fd,&store);
    else if(!s->task.phase || strcmp(r.id,s->task.id)) result=failure(&io,fd,"update_not_found");
    else if(r.op==OBD_UPDATE_STATUS) result=reply(s,&io,fd,1,0,0,can_install(s,&store));
    else if(r.op==OBD_UPDATE_APPLY) result=apply(s,&io,fd,&store);
    else if(s->task.phase==APPLYING || s->task.phase==AWAITING || s->task.phase==APPLIED || s->task.phase==RECOVERY) result=reply(s,&io,fd,0,"cannot_cancel_install",0,0);
    else { s->task.phase=CANCELLED; result=save(s)?-1:reply(s,&io,fd,1,0,0,0); }
    obd_store_close(&store); return result;
}
static int lifetime_lock(const char *root) {
    char path[384]; obd_file_metadata a,b;
    if(obd_disk_join(path,root,"update-server.lock")) return -1;
    int exists=obd_disk_exists(path); if(exists<0 || (exists && obd_disk_private(path,0,0600,0))) return -1;
    int fd=open(path,O_RDWR|O_CREAT|O_NONBLOCK,0600);
    if(fd<0) return -1;
    if(obd_disk_metadata(0,fd,&a) || obd_disk_private(path,0,0600,&b) || a.inode!=b.inode || a.device!=b.device || a.size || flock(fd,LOCK_EX|LOCK_NB)) { close(fd); return -1; }
    return fd;
}
int main(int argc,char **argv) {
    if(argc!=15 || strcmp(argv[1],"--root") || strcmp(argv[3],"--key") || strcmp(argv[5],"--key-sha256") ||
       strcmp(argv[7],"--target") || strcmp(argv[9],"--baseline") || strcmp(argv[11],"--address") || strcmp(argv[13],"--port")) return 64;
    if(strcmp(argv[12],"127.0.0.1") && strcmp(argv[12],"192.168.77.1")) return 64;
    unsigned port=0;
    if(!argv[14][0] || strlen(argv[14])>5) return 64;
    for(const char *p=argv[14];*p;p++) { if(*p<'0' || *p>'9') return 64; port=port*10+(unsigned)(*p-'0'); }
    if(port<1024 || port>65535) return 64;
#ifndef OBD_IO_HOST
    if(port!=47793) return 64;
#endif
    service s; memset(&s,0,sizeof(s)); s.root=argv[2]; s.target=argv[8];
    uint8_t pin[32],actual[32],*key=0; size_t bytes=0;
    if(obd_generation_unhex(s.baseline,argv[10],32) || obd_generation_unhex(pin,argv[6],32) ||
       obd_disk_read(argv[4],&key,&bytes,32,0) || bytes!=32) { free(key); return 65; }
    hash(key,32,actual); if(memcmp(pin,actual,32)) { free(key); return 65; }
    memcpy(s.key,key,32); free(key);
#ifndef OBD_IO_HOST
    if(strcmp(s.target,obd_baseline_target()) || obd_generation_unhex(actual,obd_baseline_zip(),32) ||
       memcmp(actual,s.baseline,32) || obd_baseline_platform_check(0)) return 78;
#endif
    (void)umask(077);
    if(signal(2,stop)==(void (*)(int))-1 || signal(15,stop)==(void (*)(int))-1 || signal(13,stop)==(void (*)(int))-1) return 71;
    obd_io_api wait; obd_io_platform(&wait);
    obd_store store; int opened;
    unsigned long long deadline=wait.now()+2000;
    while((opened=open_store(&s,&store))==OBD_STORE_BUSY && !stopping && wait.now()<deadline) {
        if(wait.poll(0,0,25)<0 && !stopping) return 71;
    }
    if(opened) return opened==OBD_STORE_BUSY?75:78;
    memcpy(s.identity,store.identity,32); obd_store_close(&store);
    int lock=lifetime_lock(s.root); if(lock<0) return 75;
    if(load(&s)) { close(lock); return 78; }
    while(!stopping && !s.poisoned) {
        obd_video_listener listener;
        if(obd_video_listen(argv[12],port,&listener)) {
            /* USB/NCM can appear after boot. No role/address changes here. */
            if(wait.poll(0,0,1000)<0 && !stopping) { close(lock); return 71; }
            continue;
        }
        while(!stopping && !s.poisoned) {
            int fd=obd_video_next(&listener,&stopping);
            if(fd==OBD_IO_TIMEOUT) continue;
            if(fd<0) break;
            (void)connection(&s,fd); close(fd);
        }
        obd_video_listener_close(&listener);
    }
    close(lock); return s.poisoned?78:0;
}
