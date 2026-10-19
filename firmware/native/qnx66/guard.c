#include "guard.h"
#include "io.h"
#include <string.h>
extern int close(int);
static int lease=-1,health=-1;
static uint64_t last_lease,last_report;
static obd_io_api io;
static unsigned submitted,posts,ready;
int obd_guard_start(int lease_fd,int health_fd) {
    if(lease>=0 || lease_fd<3 || health_fd<3 || lease_fd==health_fd) return -1;
    int a,b; obd_io_platform(&io);
    if(obd_io_nonblock(lease_fd,&a) || obd_io_nonblock(health_fd,&b) || !(last_lease=io.now())) return -1;
    lease=lease_fd; health=health_fd; last_report=0; submitted=posts=ready=0; return 0;
}
void obd_guard_stop(void) {
    if(lease>=0) close(lease);
    if(health>=0) close(health);
    lease=health=-1; submitted=posts=ready=0;
}
void obd_guard_media(const obd_media *m) {
    submitted=m?m->submitted:0; posts=m?m->posts:0;
    ready=m && m->ready && !m->failed && m->child && m->readback && m->actual_visible;
}
static void put32(uint8_t *p,unsigned v) { for(unsigned i=0;i<4;i++) p[i]=(uint8_t)(v>>(i*8)); }
int obd_guard_tick(void) {
    if(lease<0) return 0;
    uint64_t now=io.now(); if(!now || now<last_lease) return -1;
    uint8_t buffer[64];
    for(unsigned i=0;i<8;i++) {
        obd_pollfd p={lease,1,0}; int n=io.poll(&p,1,0);
        if(n<0) return -1;
        if(!n) break;
        if(p.revents&0x28) return -1;
        int bytes=io.read(lease,buffer,sizeof(buffer));
        if(bytes<=0) return -1;
        for(int j=0;j<bytes;j++) if(buffer[j]!=0xa7) return -1;
        last_lease=now;
    }
    if(now-last_lease>=3000) return -1;
    if(!last_report || now-last_report>=250) {
        uint8_t report[24]={0}; memcpy(report,"OBDH01\n",7);
        put32(report+8,ready); put32(report+12,submitted); put32(report+16,posts);
        if(io.write(health,report,sizeof(report))!=(int)sizeof(report)) return -1;
        last_report=now;
    }
    return 0;
}
