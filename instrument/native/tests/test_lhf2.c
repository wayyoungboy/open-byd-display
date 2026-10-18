#include "lhf2.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "Failed line %d: %s\n", __LINE__, #x); exit(1); } } while (0)
static const uint8_t key[] = {0,0,0,1,0x67,0x42,0,0,0,1,0x68,0xce,0,0,0,1,0x65,0x88};
static const uint8_t delta[] = {0,0,1,0x41,0x88};
static void put32(uint8_t *p, uint32_t x) { p[0]=(uint8_t)(x>>24);p[1]=(uint8_t)(x>>16);p[2]=(uint8_t)(x>>8);p[3]=(uint8_t)x; }
static obd_lhf2_frame frame(const uint8_t *p, size_t size, uint32_t seq, uint32_t pts, uint32_t is_key) {
    obd_lhf2_frame f = {(uint32_t)size,seq,pts,is_key,obd_crc32(p,size)};
    return f;
}

int main(void) {
    CHECK(obd_crc32((const uint8_t *)"123456789",9)==UINT32_C(0xcbf43926));
    uint8_t header[24] = {'L','H','F','2'};
    obd_lhf2_frame decoded;
    put32(header+4,18);put32(header+8,19);put32(header+12,23);put32(header+16,1);put32(header+20,31);
    CHECK(!obd_lhf2_header(header,24,&decoded));CHECK(decoded.bytes==18 && decoded.sequence==19 && decoded.pts_ms==23 && decoded.crc==31);
    CHECK(obd_lhf2_header(header,23,&decoded));header[0]='X';CHECK(obd_lhf2_header(header,24,&decoded));header[0]='L';
    put32(header+4,OBD_LHF2_MAX_PAYLOAD+1);CHECK(obd_lhf2_header(header,24,&decoded));
    memset(header+4,0,20);put32(header+8,UINT32_MAX);CHECK(!obd_lhf2_header(header,24,&decoded));
    CHECK(!obd_lhf2_validate(&(obd_lhf2_state){0},&decoded,NULL,0));
    put32(header+12,1);CHECK(obd_lhf2_header(header,24,&decoded));

    obd_lhf2_state state = {0};
    obd_lhf2_frame f = frame(delta,sizeof(delta),0,0,0);
    CHECK(obd_lhf2_validate(&state,&f,delta,sizeof(delta))); /* First frame needs SPS/PPS/IDR. */
    f=frame(key,sizeof(key),UINT32_MAX,UINT32_MAX-1,1);
    CHECK(!obd_lhf2_validate(&state,&f,key,sizeof(key)));CHECK(!state.has_previous);
    obd_lhf2_commit(&state,&f);CHECK(state.has_previous);
    f=frame(delta,sizeof(delta),0,0,0);CHECK(!obd_lhf2_validate(&state,&f,delta,sizeof(delta))); /* Small wrap. */
    obd_lhf2_commit(&state,&f);
    CHECK(obd_lhf2_validate(&state,&f,delta,sizeof(delta))); /* Duplicate. */
    f.sequence=2;CHECK(obd_lhf2_validate(&state,&f,delta,sizeof(delta))); /* Gap requires key. */
    f=frame(key,sizeof(key),2,1,1);CHECK(!obd_lhf2_validate(&state,&f,key,sizeof(key)));
    f.pts_ms=UINT32_MAX;CHECK(obd_lhf2_validate(&state,&f,key,sizeof(key))); /* Reversed PTS. */
    f.pts_ms=1;f.sequence=UINT32_C(0x80000000);CHECK(obd_lhf2_validate(&state,&f,key,sizeof(key)));
    f.sequence=1;f.key_frame=0;CHECK(obd_lhf2_validate(&state,&f,key,sizeof(key)));
    f.key_frame=2;CHECK(obd_lhf2_validate(&state,&f,key,sizeof(key)));
    f.key_frame=1;f.crc^=1;CHECK(obd_lhf2_validate(&state,&f,key,sizeof(key)));
    CHECK(obd_lhf2_validate(&state,&f,NULL,sizeof(key)));

    uint8_t extended[sizeof(key)+6];memcpy(extended,key,sizeof(key));
    extended[sizeof(key)]=0;extended[sizeof(key)+1]=0;extended[sizeof(key)+2]=1;extended[sizeof(key)+4]=0xaa;extended[sizeof(key)+5]=0xbb;
    for (unsigned type=0;type<32;++type) {
        extended[sizeof(key)+3]=(uint8_t)type;
        f=frame(extended,sizeof(extended),1,1,1);
        int allowed=type==1||type==5||type==6||type==7||type==8||type==9||type==12;
        CHECK((obd_lhf2_validate(&state,&f,extended,sizeof(extended))==0)==allowed);
    }
    extended[sizeof(key)+3]=0xe5;f=frame(extended,sizeof(extended),1,1,1);
    CHECK(obd_lhf2_validate(&state,&f,extended,sizeof(extended)));
    uint8_t prefixed[sizeof(key)+1];prefixed[0]=1;memcpy(prefixed+1,key,sizeof(key));
    f=frame(prefixed,sizeof(prefixed),1,1,1);CHECK(obd_lhf2_validate(&state,&f,prefixed,sizeof(prefixed)));
    CHECK(state.sequence==0 && state.pts_ms==0); /* All failures kept state unchanged. */
    puts("LHF2 CRC, framing, NAL, sequence, wrap, EOS and non-mutation checks passed");
    return 0;
}
