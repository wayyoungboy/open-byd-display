#include "generation.h"
#include "sha256.h"
#include "monocypher-ed25519.h"
#include <string.h>
static uint32_t le32(const uint8_t *p) { return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24; }
static int zero(const uint8_t *p,unsigned n) { unsigned v=0; for(unsigned i=0;i<n;i++) v|=p[i]; return !v; }
static int hex(char c) { return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:-1; }
void obd_generation_hex(char *out,const uint8_t *p,unsigned n) {
    static const char digits[]="0123456789abcdef";
    for(unsigned i=0;i<n;i++) { out[2*i]=digits[p[i]>>4]; out[2*i+1]=digits[p[i]&15]; } out[2*n]=0;
}
int obd_generation_unhex(uint8_t *out,const char *p,unsigned n) {
    if(!out || !p) return -1;
    for(unsigned i=0;i<n;i++) { if(!p[2*i] || !p[2*i+1] || hex(p[2*i])<0 || hex(p[2*i+1])<0) return -1; out[i]=(uint8_t)((hex(p[2*i])<<4)|hex(p[2*i+1])); }
    return p[2*n]?-1:0;
}
static int text(const uint8_t *p,unsigned capacity,int target) {
    unsigned i=0;
    for(;i<capacity && p[i];i++) if(!((p[i]>='a'&&p[i]<='z')||(p[i]>='A'&&p[i]<='Z')||
        (p[i]>='0'&&p[i]<='9')||p[i]=='-'||(!target&&(p[i]=='_'||p[i]=='.')))) return -1;
    if(!i || i==capacity || !zero(p+i,capacity-i) || p[0]=='.') return -1;
    return 0;
}
int obd_generation_verify(obd_generation *out,const uint8_t *raw,size_t bytes,
                          const uint8_t public_key[32],const char *target,const uint8_t baseline[32]) {
    if(!out) return -1;
    memset(out,0,sizeof(*out));
    if(!raw || !public_key || !target || !baseline || bytes<OBD_GEN_HEADER || bytes>OBD_GEN_HEADER+OBD_GEN_ROW*OBD_GEN_FILES+OBD_GEN_PAYLOAD ||
       memcmp(raw,"OBDGEN1\n",8) || text(raw+16,32,1) || strcmp((const char *)raw+16,target) || memcmp(raw+48,baseline,32) ||
       zero(public_key,32) || zero(baseline,32) || zero(raw+80,16) || !zero(raw+102,2) || !zero(raw+108,4)) return -1;
    unsigned count=le32(raw+12),payload=le32(raw+8),table=le32(raw+104);
    if(!count || count>OBD_GEN_FILES || !payload || payload>OBD_GEN_PAYLOAD || table!=count*OBD_GEN_ROW ||
       bytes!=OBD_GEN_HEADER+table+payload) return -1;
    uint8_t digest[32]; obd_sha256 hash;
    obd_sha256_init(&hash); obd_sha256_update(&hash,raw+OBD_GEN_HEADER,table); obd_sha256_final(&hash,digest);
    if(memcmp(digest,raw+112,32)) return -1;
    static const uint8_t domain[]="OBD-NATIVE-GENERATION-V1\n";
    uint8_t signed_bytes[sizeof(domain)-1+144+OBD_GEN_FILES*OBD_GEN_ROW];
    memcpy(signed_bytes,domain,sizeof(domain)-1); memcpy(signed_bytes+sizeof(domain)-1,raw,144);
    memcpy(signed_bytes+sizeof(domain)-1+144,raw+OBD_GEN_HEADER,table);
    if(crypto_ed25519_check(raw+144,public_key,signed_bytes,sizeof(domain)-1+144+table)) return -1;
    unsigned at=0; int service=0,profile=0;
    for(unsigned i=0;i<count;i++) {
        const uint8_t *row=raw+OBD_GEN_HEADER+i*OBD_GEN_ROW;
        obd_generation_file *f=out->files+i;
        if(text(row,64,0) || !zero(row+76,20)) goto fail;
        memcpy(f->name,row,64); f->bytes=le32(row+64); f->mode=le32(row+68); f->offset=le32(row+72);
        memcpy(f->sha256,row+96,32);
        if(!f->bytes || f->bytes>payload-at || f->offset!=at || (f->mode!=0600 && f->mode!=0700)) goto fail;
        if(i && strcmp(out->files[i-1].name,f->name)>=0) goto fail;
        if(!strcmp(f->name,"service.elf")) { if(f->mode!=0700) goto fail; service=1; }
        if(!strcmp(f->name,"profile.dat")) { if(f->mode!=0600) goto fail; profile=1; }
        if(!strcmp(f->name,"bundle.obg") || !strcmp(f->name,"state.bin") || !strcmp(f->name,"identity.bin") || !strcmp(f->name,"store.lock")) goto fail;
        obd_sha256_init(&hash); obd_sha256_update(&hash,raw+OBD_GEN_HEADER+table+at,f->bytes); obd_sha256_final(&hash,digest);
        if(memcmp(digest,f->sha256,32)) goto fail;
        at+=f->bytes;
    }
    if(at!=payload || !service || !profile) goto fail;
    memcpy(out->target,raw+16,32); memcpy(out->baseline,baseline,32);
    out->slot.present=1; obd_generation_hex(out->slot.package_id,raw+80,16);
    for(unsigned i=0;i<3;i++) out->slot.version[i]=(uint16_t)((unsigned)raw[96+2*i]|(unsigned)raw[97+2*i]<<8);
    obd_sha256_init(&hash); obd_sha256_update(&hash,raw,bytes); obd_sha256_final(&hash,out->slot.tree_sha256);
    out->payload=raw+OBD_GEN_HEADER+table; out->count=count; return 0;
fail:
    memset(out,0,sizeof(*out)); return -1;
}
