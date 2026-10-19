#include "store.h"
#include "disk.h"
#include "sha256.h"
#include "baseline_platform.h"
#include <string.h>
extern void free(void *);
#ifdef OBD_IO_HOST
#include <unistd.h>
#else
extern int write(int,const void *,unsigned);
#endif
extern int snprintf(char *,size_t,const char *,...);
#ifdef OBD_STORE_FAULT_TEST
static int test_health(void *context,const obd_trial_slot *slot) { (void)slot; return strcmp(context,"HOST_TEST_HEALTHY")? -1:0; }
#endif
static int emit(const char *op,const obd_store *store,const char *package,int selection) {
    char text[1024]; char nonce[33]; obd_generation_hex(nonce,store->state.nonce,16);
    int n=snprintf(text,sizeof(text),"{\"service\":\"OpenBYDDisplay-native-store\",\"op\":\"%s\",\"target\":\"%s\",\"package_id\":\"%s\",\"revision\":%u,\"current\":\"%s\",\"candidate\":\"%s\",\"attempted\":%u,\"nonce\":\"%s\",\"selection\":%d,\"production_flashable\":false,\"qnx_execution_verified\":false}\n",op,store->target,package?package:"",store->state.revision,store->state.current.package_id,store->state.candidate.package_id,store->state.attempted,nonce,selection);
    return n<=0 || (size_t)n>=sizeof(text) || write(1,text,(unsigned)n)!=n?74:0;
}
int main(int argc,char **argv) {
    /* Exact option order prevents ambiguous flags on the small target CLI. */
    if(argc<11 || strcmp(argv[1],"--root") || strcmp(argv[3],"--key") || strcmp(argv[5],"--key-sha256") ||
       strcmp(argv[7],"--target") || strcmp(argv[9],"--baseline")) return 64;
    if(argc<12) return 64;
    const char *op=argv[11];
    int valid=(!strcmp(op,"init") && argc==12) || (!strcmp(op,"status") && argc==12) ||
        (!strcmp(op,"stage") && argc==13) || (!strcmp(op,"activate") && argc==14);
#ifdef OBD_STORE_FAULT_TEST
    valid=valid || (!strcmp(op,"select") && argc==12) || (!strcmp(op,"confirm-test") && argc==15) ||
        (!strcmp(op,"hold-lock-test") && argc==12);
#endif
    if(!valid) return 64;
    uint8_t baseline[32],key_sha[32],actual[32],*public_key=0; size_t key_bytes=0;
    if(obd_generation_unhex(baseline,argv[10],32) || obd_generation_unhex(key_sha,argv[6],32) ||
       obd_disk_read(argv[4],&public_key,&key_bytes,32,0) || key_bytes!=32) { free(public_key); return 65; }
    obd_sha256 hash; obd_sha256_init(&hash); obd_sha256_update(&hash,public_key,32); obd_sha256_final(&hash,actual);
    if(memcmp(actual,key_sha,32)) { free(public_key); return 65; }
#ifndef OBD_IO_HOST
    uint8_t compiled[32];
    if(strcmp(argv[8],obd_baseline_target()) || obd_generation_unhex(compiled,obd_baseline_zip(),32) ||
       memcmp(compiled,baseline,32) || obd_baseline_platform_check(0)) { free(public_key); return 78; }
#endif
    int result=64;
    if(!strcmp(op,"init") && argc==12) {
        result=obd_store_init(argv[2],argv[8],baseline,public_key); free(public_key); return result?78:0;
    }
    obd_store store;
    int opened=obd_store_open(&store,argv[2],argv[8],baseline,public_key);
    if(opened) { free(public_key); return opened==OBD_STORE_BUSY?75:78; }
    free(public_key);
    char id[33]={0};
    if(!strcmp(op,"status") && argc==12) result=emit(op,&store,0,0);
    else if(!strcmp(op,"stage") && argc==13) {
        uint8_t *raw=0; size_t bytes=0;
        result=obd_disk_read(argv[12],&raw,&bytes,OBD_GEN_HEADER+OBD_GEN_ROW*OBD_GEN_FILES+OBD_GEN_PAYLOAD,0);
        if(!result) result=obd_store_stage(&store,raw,bytes,id);
        free(raw); if(!result) result=emit(op,&store,id,0);
    } else if(!strcmp(op,"activate") && argc==14) {
        uint8_t nonce[16]; result=obd_generation_unhex(nonce,argv[13],16);
        if(!result) result=obd_store_activate(&store,argv[12],nonce);
        if(!result) result=emit(op,&store,argv[12],0);
    }
#ifdef OBD_STORE_FAULT_TEST
    else if(!strcmp(op,"select") && argc==12) {
        obd_trial_slot selected; char path[384];
        result=obd_store_select(&store,&selected,path);
        if(result>=0) result=emit(op,&store,selected.package_id,result);
    }
    else if(!strcmp(op,"confirm-test") && argc==15) {
        uint8_t nonce[16]; result=obd_generation_unhex(nonce,argv[13],16);
        if(!result) result=obd_store_confirm(&store,argv[12],nonce,test_health,argv[14]);
        if(!result) result=emit(op,&store,argv[12],0);
    } else if(!strcmp(op,"hold-lock-test") && argc==12) { result=emit(op,&store,0,0); if(!result) sleep(3); }
#endif
    obd_store_close(&store); return result?78:0;
}
