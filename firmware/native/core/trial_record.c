#include "trial_record.h"
#include "generation.h"
#include "sha256.h"
#include <string.h>
static void put32(uint8_t *p,uint32_t v) { for(unsigned i=0;i<4;i++) p[i]=(uint8_t)(v>>(8*i)); }
static uint32_t get32(const uint8_t *p) { return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24; }
static void versions(uint8_t *p,const uint16_t v[3]) { for(unsigned i=0;i<3;i++) { p[2*i]=(uint8_t)v[i]; p[2*i+1]=(uint8_t)(v[i]>>8); } }
static void read_versions(uint16_t v[3],const uint8_t *p) { for(unsigned i=0;i<3;i++) v[i]=(uint16_t)((unsigned)p[2*i]|(unsigned)p[2*i+1]<<8); }
static int zero(const uint8_t *p,unsigned n) { unsigned v=0; for(unsigned i=0;i<n;i++) v|=p[i]; return !v; }
int obd_trial_encode(uint8_t out[OBD_TRIAL_RECORD_BYTES],const obd_trial_state *s) {
    if(!out || obd_trial_validate(s)) return -1;
    memset(out,0,OBD_TRIAL_RECORD_BYTES); memcpy(out,"OBDST01\n",8); put32(out+8,s->revision);
    put32(out+12,s->highwater_present|(s->current.present<<1)|(s->candidate.present<<2)|(s->attempted<<3));
    memcpy(out+16,s->identity_sha256,32); versions(out+48,s->highwater);
    const obd_trial_slot *slots[2]={&s->current,&s->candidate};
    for(unsigned i=0;i<2;i++) {
        uint8_t *p=out+56+i*56; const obd_trial_slot *slot=slots[i];
        if(slot->present && obd_generation_unhex(p,slot->package_id,16)) return -1;
        versions(p+16,slot->version); memcpy(p+24,slot->tree_sha256,32);
    }
    memcpy(out+168,s->nonce,16);
    obd_sha256 hash; obd_sha256_init(&hash); obd_sha256_update(&hash,out,224); obd_sha256_final(&hash,out+224); return 0;
}
int obd_trial_decode(obd_trial_state *s,const uint8_t *raw,unsigned bytes) {
    if(!s) return -1;
    memset(s,0,sizeof(*s));
    if(!raw || bytes!=OBD_TRIAL_RECORD_BYTES || memcmp(raw,"OBDST01\n",8) || get32(raw+12)>15 ||
       !zero(raw+54,2) || !zero(raw+78,2) || !zero(raw+134,2) || !zero(raw+184,40)) return -1;
    uint8_t digest[32]; obd_sha256 hash;
    obd_sha256_init(&hash); obd_sha256_update(&hash,raw,224); obd_sha256_final(&hash,digest);
    if(memcmp(digest,raw+224,32)) return -1;
    s->format=1; s->revision=get32(raw+8); unsigned flags=get32(raw+12);
    s->highwater_present=flags&1; s->current.present=(flags>>1)&1; s->candidate.present=(flags>>2)&1; s->attempted=(flags>>3)&1;
    memcpy(s->identity_sha256,raw+16,32); read_versions(s->highwater,raw+48);
    obd_trial_slot *slots[2]={&s->current,&s->candidate};
    for(unsigned i=0;i<2;i++) {
        const uint8_t *p=raw+56+i*56; obd_trial_slot *slot=slots[i];
        if(slot->present) obd_generation_hex(slot->package_id,p,16);
        else if(!zero(p,56)) goto fail;
        read_versions(slot->version,p+16); memcpy(slot->tree_sha256,p+24,32);
    }
    memcpy(s->nonce,raw+168,16);
    if(!obd_trial_validate(s)) return 0;
fail:
    memset(s,0,sizeof(*s)); return -1;
}
