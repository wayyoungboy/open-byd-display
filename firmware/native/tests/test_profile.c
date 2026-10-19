#include "profile.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"profile failed at %d\n",__LINE__); exit(1); } } while(0)
static void put(uint8_t *p,unsigned v) { p[0]=(uint8_t)v; p[1]=(uint8_t)(v>>8); }
int main(void) {
    uint8_t raw[64]={0}; memcpy(raw,"OBDPRO1\n",8); raw[8]=raw[9]=1;
    raw[12]=192; raw[13]=168; raw[14]=77; raw[15]=1;
    unsigned values[]={2,1920,720,700,180,520,360,3,120,47792};
    for(unsigned i=0;i<10;i++) put(raw+16+2*i,values[i]);
    obd_service_profile p;
    CHECK(!obd_profile_decode(&p,raw,"song-plus-dmi-2021") && p.enabled && !p.socket_environment[0]);
    raw[10]=1; raw[36]=0xab;
    CHECK(!obd_profile_decode(&p,raw,"song-plus-dmi-2021"));
    CHECK(!strcmp(p.socket_environment,"SOCK=/fs/tmpfs/OpenBYDDisplay/song-plus-dmi-2021/ab000000000000000000000000000000/net"));
    CHECK(obd_profile_decode(&p,raw,"tang-plus-dmi-2021")); CHECK(!p.enabled && !p.socket_environment[0]);
    raw[8]=2; CHECK(!obd_profile_decode(&p,raw,"tang-plus-dmi-2021"));
    CHECK(strstr(p.socket_environment,"tang-plus-dmi-2021"));
    raw[63]=1; CHECK(obd_profile_decode(&p,raw,"tang-plus-dmi-2021")); raw[63]=0;
    raw[36]=0; CHECK(obd_profile_decode(&p,raw,"tang-plus-dmi-2021")); raw[10]=0;
    put(raw+30,10); CHECK(obd_profile_decode(&p,raw,"tang-plus-dmi-2021"));
    puts("signed profile model/bounds and explicit private SOCK namespace passed"); return 0;
}
