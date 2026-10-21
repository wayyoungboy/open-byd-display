#ifdef OBD_IO_HOST
#define _POSIX_C_SOURCE 200809L
#ifdef __APPLE__
#define _DARWIN_C_SOURCE
#endif
#endif
#include "store.h"
#include "disk.h"
#include "trial_record.h"
#include "sha256.h"
#include <string.h>
#ifdef OBD_IO_HOST
#include <unistd.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#else
extern int open(const char *,int,...);
extern int close(int);
extern int mkdir(const char *,unsigned);
extern int chmod(const char *,unsigned);
extern int rename(const char *,const char *);
extern int unlink(const char *);
extern int flock(int,int);
extern unsigned umask(unsigned);
extern int *__get_errno_ptr(void);
extern void free(void *);
enum { O_RDWR=2,O_CREAT=0x100,O_NONBLOCK=0x80,LOCK_EX=2,LOCK_NB=4 };
#endif
#ifndef OBD_IO_HOST
extern int snprintf(char *,size_t,const char *,...);
#endif
static int id_valid(const char *id) { uint8_t parsed[16]; return id && !obd_generation_unhex(parsed,id,16); }
static int target_valid(const char *s) {
    return s && (!strcmp(s,"song-plus-dmi-2021") || !strcmp(s,"tang-plus-dmi-2021"));
}
static int root_valid(const char *s) {
    if(!s || s[0]!='/' || !s[1]) return 0;
    const char *reserved[]={"/dev","/proc","/boot","/bin","/sbin","/lib","/install","/base/scripts","/base/etc","/base/app/rc_hmi"};
    for(unsigned i=0;i<sizeof(reserved)/sizeof(reserved[0]);i++) {
        unsigned size=0; while(reserved[i][size]) size++;
        unsigned same=0; while(s[same] && same<size && s[same]==reserved[i][same]) same++;
        if(same==size && (!s[size] || s[size]=='/')) return 0;
    }
    unsigned n=0,part=1;
    for(;s[n];n++) {
        if(n>=240 || s[n]=='\n' || s[n]=='\r') return 0;
        if(s[n]=='/' && n) {
            unsigned size=n-part;
            if(!size || (size==1 && s[part]=='.') || (size==2 && s[part]=='.' && s[part+1]=='.')) return 0;
            part=n+1;
        }
    }
    return n>part && !(n-part==1 && s[part]=='.') && !(n-part==2 && s[part]=='.' && s[part+1]=='.') && !obd_disk_parents(s);
}
static int nonzero(const uint8_t *v) { unsigned x=0; if(!v) return 0; for(unsigned i=0;i<32;i++) x|=v[i]; return x!=0; }
static void identity_data(uint8_t record[104],const char *target,const uint8_t baseline[32],const uint8_t key[32],uint8_t identity[32]) {
    memset(record,0,104); memcpy(record,"OBDID01\n",8);
    unsigned n=0; while(target[n]) n++; memcpy(record+8,target,n); memcpy(record+40,baseline,32); memcpy(record+72,key,32);
    obd_sha256 hash; obd_sha256_init(&hash); obd_sha256_update(&hash,record,104); obd_sha256_final(&hash,identity);
}
static int load_state(obd_store *s,obd_trial_state *out) {
    char path[384]; uint8_t *raw=0; size_t bytes=0;
    if(obd_disk_join(path,s->root,"state.bin") || obd_disk_read(path,&raw,&bytes,256,1)) return -1;
    int result=obd_trial_decode(out,raw,(unsigned)bytes); free(raw);
    return result || memcmp(out->identity_sha256,s->identity,32)?-1:0;
}
static int commit_state(void *context,uint32_t expected,const obd_trial_state *next) {
    obd_store *s=context; obd_trial_state current; uint8_t record[256]; char pending[384],path[384];
    if(!s || s->poisoned || load_state(s,&current) || current.revision!=expected || obd_trial_encode(record,next) ||
       obd_disk_join(pending,s->root,"state.next") || obd_disk_join(path,s->root,"state.bin")) return -1;
    s->poisoned=1; /* Ambiguous storage result forces close/reload, never handoff. */
    if(obd_disk_create(pending,record,sizeof(record),0600)) return -1;
    obd_disk_checkpoint("state-file-synced");
    if(rename(pending,path)) return -1;
    obd_disk_checkpoint("state-renamed");
    if(obd_disk_sync(s->root)) return -1;
    obd_disk_checkpoint("state-directory-synced"); s->poisoned=0; return 0;
}
int obd_store_init(const char *root,const char *target,const uint8_t baseline[32],const uint8_t key[32]) {
    if(!root_valid(root) || !target_valid(target) || !nonzero(baseline) || !nonzero(key)) return -1;
    uint8_t record[104],state_raw[256]; obd_trial_state state; memset(&state,0,sizeof(state));
    identity_data(record,target,baseline,key,state.identity_sha256); state.format=1; state.revision=1;
    if(obd_trial_encode(state_raw,&state)) return -1;
    (void)umask(077);
    if(mkdir(root,0700) || obd_disk_private(root,1,0700,0) || obd_disk_sync_parent(root)) return -1;
    char path[384];
    if(obd_disk_join(path,root,"identity.bin") || obd_disk_create(path,record,sizeof(record),0600)) return -1;
    obd_disk_checkpoint("identity-file-synced");
    if(obd_disk_join(path,root,"state.bin") || obd_disk_create(path,state_raw,sizeof(state_raw),0600) || obd_disk_sync(root)) return -1;
    return 0;
}
int obd_store_open(obd_store *s,const char *root,const char *target,const uint8_t baseline[32],const uint8_t key[32]) {
    if(!s) return -1;
    memset(s,0,sizeof(*s)); s->lock_fd=-1;
    if(!root_valid(root) || !target_valid(target) || !nonzero(baseline) || !nonzero(key) || obd_disk_private(root,1,0700,0)) return -1;
    if(snprintf(s->root,sizeof(s->root),"%s",root)<0 || snprintf(s->target,sizeof(s->target),"%s",target)<0) return -1;
    memcpy(s->public_key,key,32); memcpy(s->baseline,baseline,32);
    uint8_t identity_record[104]; identity_data(identity_record,target,baseline,key,s->identity);
    char path[384]; uint8_t *raw=0; size_t bytes=0;
    if(obd_disk_join(path,root,"store.lock")) return -1;
    obd_file_metadata before,after;
    int exists=obd_disk_exists(path);
    if(exists<0 || (exists && obd_disk_private(path,0,0600,0))) return -1;
    (void)umask(077); s->lock_fd=open(path,O_RDWR|O_CREAT|O_NONBLOCK,0600);
    if(s->lock_fd<0) return -1;
    if(obd_disk_metadata(0,s->lock_fd,&before) || obd_disk_private(path,0,0600,&after) ||
       before.inode!=after.inode || before.device!=after.device || before.size) goto fail;
    if(flock(s->lock_fd,LOCK_EX|LOCK_NB)) {
#ifdef OBD_IO_HOST
        int busy=errno==EWOULDBLOCK || errno==EAGAIN;
#else
        int busy=*__get_errno_ptr()==11;
#endif
        obd_store_close(s); return busy?OBD_STORE_BUSY:-1;
    }
    if(obd_disk_join(path,root,"identity.bin") || obd_disk_read(path,&raw,&bytes,104,1)) goto fail;
    int matching=bytes==104 && !memcmp(raw,identity_record,104); free(raw); raw=0;
    if(!matching || load_state(s,&s->state)) goto fail;
    /* A valid primary is authoritative. A never-published temp cannot be used
     * as a selected slot; remove only our fixed private regular temp. */
    if(obd_disk_join(path,root,"state.next")) goto fail;
    exists=obd_disk_exists(path); if(exists<0) goto fail;
    if(exists) {
        if(obd_disk_private(path,0,0600,0) || unlink(path) || obd_disk_sync(root)) goto fail;
    }
    return 0;
fail:
    free(raw); obd_store_close(s); return -1;
}
void obd_store_close(obd_store *s) { if(s && s->lock_fd>=0) { close(s->lock_fd); s->lock_fd=-1; } }
static int gen_path(char out[384],obd_store *s,const char *prefix,const char *id) {
    if(!id_valid(id)) return -1;
    char name[64]; int n=snprintf(name,sizeof(name),"%s-%s",prefix,id);
    return n<0 || (unsigned)n>=sizeof(name) || obd_disk_join(out,s->root,name)?-1:0;
}
static int verify_directory(obd_store *s,const char *path,const obd_trial_slot *expected,obd_generation *generation,uint8_t **bundle) {
    if(s->poisoned || obd_disk_private(path,1,0500,0)) return -1;
    char name[384]; size_t bytes=0;
    if(obd_disk_join(name,path,"bundle.obg") || obd_disk_read(name,bundle,&bytes,OBD_GEN_HEADER+OBD_GEN_FILES*OBD_GEN_ROW+OBD_GEN_PAYLOAD,1)) return -1;
    if(obd_generation_verify(generation,*bundle,bytes,s->public_key,s->target,s->baseline) ||
       (expected && (strcmp(expected->package_id,generation->slot.package_id) ||
        memcmp(expected->version,generation->slot.version,6) || memcmp(expected->tree_sha256,generation->slot.tree_sha256,32)))) goto fail;
    char names[20][64]; unsigned count;
    if(obd_disk_names(path,names,&count) || count!=generation->count+1) goto fail;
    for(unsigned i=0;i<count;i++) {
        int found=!strcmp(names[i],"bundle.obg");
        for(unsigned j=0;j<generation->count;j++) if(!strcmp(names[i],generation->files[j].name)) found=1;
        if(!found) goto fail;
    }
    for(unsigned i=0;i<generation->count;i++) {
        const obd_generation_file *f=generation->files+i; uint8_t *content=0; size_t n=0;
        if(obd_disk_join(name,path,f->name) || obd_disk_private(name,0,f->mode,0) || obd_disk_read(name,&content,&n,f->bytes,0)) goto fail;
        int mismatch=n!=f->bytes || memcmp(content,generation->payload+f->offset,n); free(content);
        if(mismatch) goto fail;
    }
    return 0;
fail:
    free(*bundle); *bundle=0; return -1;
}
int obd_store_verify_slot(void *context,const uint8_t identity[32],const obd_trial_slot *slot) {
    obd_store *s=context; char path[384]; obd_generation g; uint8_t *bundle=0;
    if(!s || s->poisoned || memcmp(identity,s->identity,32) || !slot || !slot->present || gen_path(path,s,"gen",slot->package_id)) return -1;
    int result=verify_directory(s,path,slot,&g,&bundle); free(bundle); return result;
}
int obd_store_stage(obd_store *s,const uint8_t *raw,size_t bytes,char id[33]) {
    obd_generation g; char final[384],pending[384],path[384]; obd_file_metadata info;
    if(!s || s->poisoned || !id || obd_generation_verify(&g,raw,bytes,s->public_key,s->target,s->baseline) ||
       gen_path(final,s,"gen",g.slot.package_id) || gen_path(pending,s,"pending",g.slot.package_id)) return -1;
    int exists=obd_disk_exists(final); if(exists<0) return -1;
    if(exists) {
        uint8_t *existing=0; obd_generation verified;
        int rc=verify_directory(s,final,&g.slot,&verified,&existing); free(existing);
        if(rc) return -1;
        memcpy(id,g.slot.package_id,33); return 0;
    }
    exists=obd_disk_exists(pending); if(exists<0) return -1;
    if(exists) {
        /* Interrupted staging is never resumed from untrusted partial bytes.
         * Preserve it for inspection and reconstruct from verified input. */
        char quarantine[384];
        if(obd_disk_metadata(pending,-1,&info) || ((info.mode&07777)!=0700 && (info.mode&07777)!=0500) || (info.mode&0170000)!=0040000 ||
           obd_disk_private(pending,1,info.mode&07777,0) || gen_path(quarantine,s,"quarantine",g.slot.package_id) ||
           obd_disk_exists(quarantine)!=0 || rename(pending,quarantine) || obd_disk_sync(s->root)) return -1;
    }
    if(mkdir(pending,0700) || obd_disk_private(pending,1,0700,0)) return -1;
    obd_disk_checkpoint("stage-directory-created");
    if(obd_disk_join(path,pending,"bundle.obg") || obd_disk_create(path,raw,(unsigned)bytes,0600)) return -1;
    obd_disk_checkpoint("stage-bundle-synced");
    for(unsigned i=0;i<g.count;i++) {
        const obd_generation_file *f=g.files+i;
        if(obd_disk_join(path,pending,f->name) || obd_disk_create(path,g.payload+f->offset,f->bytes,f->mode)) return -1;
        obd_disk_checkpoint("stage-file-synced");
    }
    if(chmod(pending,0500) || obd_disk_sync(pending)) return -1;
    obd_disk_checkpoint("stage-complete-synced");
    if(rename(pending,final)) return -1;
    obd_disk_checkpoint("generation-renamed");
    if(obd_disk_sync(s->root)) { s->poisoned=1; return -1; }
    obd_disk_checkpoint("generation-directory-synced");
    uint8_t *verified=0; obd_generation checked;
    int rc=verify_directory(s,final,&g.slot,&checked,&verified); free(verified);
    if(rc) return -1;
    memcpy(id,g.slot.package_id,33); return 0;
}
int obd_store_activate(obd_store *s,const char *id,const uint8_t nonce[16]) {
    char path[384]; uint8_t *raw=0; obd_generation g;
    if(!s || s->poisoned || gen_path(path,s,"gen",id) || verify_directory(s,path,0,&g,&raw)) return -1;
    obd_trial_slot slot=g.slot; free(raw);
    const obd_trial_api api={s,obd_store_verify_slot,commit_state,0};
    return obd_trial_activate(&s->state,&slot,nonce,&api);
}
int obd_store_inspect(obd_store *s,const char *id,obd_trial_slot *slot,uint8_t profile[64]) {
    char path[384]; uint8_t *raw=0; obd_generation g;
    if(!s || !slot || !profile || gen_path(path,s,"gen",id) || verify_directory(s,path,0,&g,&raw)) return -1;
    int result=-1;
    for(unsigned i=0;i<g.count;i++) if(!strcmp(g.files[i].name,"profile.dat") && g.files[i].bytes==64) {
        *slot=g.slot; memcpy(profile,g.payload+g.files[i].offset,64); result=0; break;
    }
    free(raw); return result;
}
int obd_store_select(obd_store *s,obd_trial_slot *slot,char path[384]) {
    if(!s || s->poisoned || !slot || !path) return -1;
    path[0]=0; const obd_trial_api api={s,obd_store_verify_slot,commit_state,0};
    int result=obd_trial_boot(&s->state,slot,&api);
    if((result==OBD_TRIAL_CURRENT || result==OBD_TRIAL_CANDIDATE) && gen_path(path,s,"gen",slot->package_id)) return -1;
    return result;
}
typedef struct { obd_store *store; int (*healthy)(void *,const obd_trial_slot *); void *context; } confirmation;
static int confirm_verify(void *v,const uint8_t id[32],const obd_trial_slot *slot) { confirmation *c=v; return obd_store_verify_slot(c->store,id,slot); }
static int confirm_commit(void *v,uint32_t revision,const obd_trial_state *next) { confirmation *c=v; return commit_state(c->store,revision,next); }
static int confirm_health(void *v,const obd_trial_slot *slot) { confirmation *c=v; return c->healthy(c->context,slot); }
int obd_store_confirm(obd_store *s,const char *id,const uint8_t nonce[16],int (*healthy)(void *,const obd_trial_slot *),void *context) {
    if(!s || s->poisoned || !healthy) return -1;
    confirmation c={s,healthy,context}; const obd_trial_api api={&c,confirm_verify,confirm_commit,confirm_health};
    return obd_trial_confirm(&s->state,id,nonce,&api);
}
int obd_store_current_profile(obd_store *s,const obd_trial_slot *slot,uint8_t profile[64]) {
    char path[384]; obd_generation g; uint8_t *raw=0; int result=-1;
    if(!s || !slot || !profile || gen_path(path,s,"gen",slot->package_id) || verify_directory(s,path,slot,&g,&raw)) return -1;
    for(unsigned i=0;i<g.count;i++) if(!strcmp(g.files[i].name,"profile.dat") && g.files[i].bytes==64) {
        memcpy(profile,g.payload+g.files[i].offset,64); result=0;
    }
    free(raw); return result;
}
