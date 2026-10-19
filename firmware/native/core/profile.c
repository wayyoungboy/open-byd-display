#include "profile.h"
#include <string.h>
extern int snprintf(char *,size_t,const char *,...);
static unsigned u16(const uint8_t *p) { return (unsigned)p[0]|(unsigned)p[1]<<8; }
int obd_profile_decode(obd_service_profile *out,const uint8_t raw[64],const char *target) {
    if(!out || !raw || !target) return -1;
    memset(out,0,sizeof(*out));
    unsigned model=!strcmp(target,"song-plus-dmi-2021")?1:!strcmp(target,"tang-plus-dmi-2021")?2:0;
    if(!model || memcmp(raw,"OBDPRO1\n",8) || raw[8]!=model || raw[9]>1 || raw[10]>1 || raw[11]) return -1;
    for(unsigned i=raw[10]?52:36;i<64;i++) if(raw[i]) return -1;
    /* Only loopback or the explicitly profiled NCM address; no wildcard bind. */
    if(memcmp(raw+12,"\177\0\0\1",4) && memcmp(raw+12,"\300\250\115\1",4)) return -1;
    out->enabled=raw[9]; out->display=u16(raw+16);
    out->physical_width=u16(raw+18); out->physical_height=u16(raw+20);
    out->x=u16(raw+22); out->y=u16(raw+24); out->width=u16(raw+26); out->height=u16(raw+28);
    out->zorder=u16(raw+30); out->trial_seconds=u16(raw+32);
    if(u16(raw+34)!=47792 || out->display>16 || out->physical_width<2 || out->physical_width>1920 ||
       out->physical_height<2 || out->physical_height>1080 || out->width<2 || out->height<2 ||
       out->width>out->physical_width || out->height>out->physical_height ||
       out->x>out->physical_width-out->width || out->y>out->physical_height-out->height ||
       out->zorder>9 || out->trial_seconds<30 || out->trial_seconds>300) { memset(out,0,sizeof(*out)); return -1; }
    /* Default z-order is below the observed OEM Kanzi z=10. This is not proof
     * that a window below OEM is actually visible; POST/readback stay separate. */
    memcpy(out->address,raw[12]==127?"127.0.0.1":"192.168.77.1",raw[12]==127?10:13);
    if(raw[10]) {
        char id[33]; unsigned nonzero=0; static const char digits[]="0123456789abcdef";
        for(unsigned i=0;i<16;i++) { nonzero|=raw[36+i]; id[2*i]=digits[raw[36+i]>>4]; id[2*i+1]=digits[raw[36+i]&15]; }
        id[32]=0;
        if(!nonzero || raw[12]!=192) { memset(out,0,sizeof(*out)); return -1; }
        int n=snprintf(out->socket_environment,sizeof(out->socket_environment),"SOCK=/fs/tmpfs/OpenBYDDisplay/%s/%s/net",target,id);
        if(n<0 || (size_t)n>=sizeof(out->socket_environment)) { memset(out,0,sizeof(*out)); return -1; }
    }
    return 0;
}
