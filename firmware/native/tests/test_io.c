#define _POSIX_C_SOURCE 200809L
#include "io.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define CHECK(x) do { if(!(x)) { fprintf(stderr,"I/O check failed at %d: %s\n",__LINE__,#x); exit(1); } } while(0)

static uint64_t tick;
static volatile int cancelled;
static int last_error, polls, calls, offset, scenario;
static const unsigned char expected[]="abcdef";
static unsigned char captured[6];
static int actions[]={-EINTR,-EAGAIN,2,1,3};
static int fake_error(void) { return last_error; }
static uint64_t fake_now(void) { return ++tick; }
static int fake_poll(obd_pollfd *p,unsigned n,int timeout) {
    CHECK(n==1 && p->fd==3 && timeout>0 && timeout<=5000);
    ++polls;
    if(scenario==1) return 0;
    if(scenario==2 && polls==1) { last_error=EINTR; return -1; }
    if(scenario==3) { cancelled=1; p->revents=p->events; return 1; }
    if(scenario==4) { p->revents=0x20; return 1; }
    p->revents=p->events;
    return 1;
}
static int fake_transfer(void *p,unsigned n,int writing) {
    ++calls;
    if(scenario==5) return 0;
    if(scenario==6) return (int)n+1;
    int count=actions[calls-1];
    if(count<0) { last_error=-count; return -1; }
    CHECK((unsigned)count<=n && offset+count<=6);
    if(writing) memcpy(captured+offset,p,(unsigned)count);
    else memcpy(p,expected+offset,(unsigned)count);
    offset+=count;
    return count;
}
static int fake_read(int fd,void *p,unsigned n) {
    CHECK(fd==3); return fake_transfer(p,n,0);
}
static int fake_write(int fd,const void *p,unsigned n) {
    CHECK(fd==3); return fake_transfer((void *)p,n,1);
}
static obd_io model(int selected) {
    tick=100; cancelled=0; last_error=polls=calls=offset=0; scenario=selected;
    memset(captured,0,sizeof(captured));
    obd_io io={{fake_read,fake_write,fake_poll,fake_error,fake_now},200,&cancelled};
    return io;
}
static void test_model(void) {
    unsigned char buffer[6]={0};
    obd_io io=model(0);
    CHECK(!obd_io_exact(&io,3,buffer,6,0));
    CHECK(calls==5 && polls==5 && !memcmp(buffer,expected,6));
    io=model(0);
    CHECK(!obd_io_exact(&io,3,(void *)expected,6,1));
    CHECK(calls==5 && !memcmp(captured,expected,6));
    io=model(2);
    CHECK(!obd_io_exact(&io,3,buffer,6,0) && polls==6);
    for(int bad=1;bad<=6;bad++) {
        if(bad==2) continue;
        io=model(bad);
        CHECK(obd_io_exact(&io,3,buffer,6,0)<0);
        if(bad<5) CHECK(!calls);
    }
    io=model(0); cancelled=1;
    CHECK(obd_io_exact(&io,3,buffer,6,1)<0 && !polls && !calls);
    io=model(0); io.deadline=101;
    CHECK(obd_io_exact(&io,3,buffer,6,1)<0 && !polls && !calls);
    io=model(0); io.deadline=104;
    CHECK(obd_io_exact(&io,3,buffer,6,1)<0 && calls==3 && offset==2);
}
static void test_platform(void) {
    int descriptors[2],flags;
    CHECK(!pipe(descriptors));
    int original=fcntl(descriptors[0],F_GETFL);
    CHECK(original>=0 && !obd_io_nonblock(descriptors[0],&flags));
    CHECK(flags==original && fcntl(descriptors[0],F_GETFL)&O_NONBLOCK);
    obd_io io; obd_io_platform(&io.api); io.cancel=0;
    unsigned char buffer[6];
    CHECK(io.api.read(descriptors[0],buffer,6)<0 && obd_io_retry(&io));
    CHECK(write(descriptors[1],expected,6)==6);
    io.deadline=io.api.now()+100;
    CHECK(!obd_io_exact(&io,descriptors[0],buffer,6,0));
    CHECK(!memcmp(buffer,expected,6));
    close(descriptors[1]);
    CHECK(obd_io_exact(&io,descriptors[0],buffer,1,0)<0);
    CHECK(!obd_io_restore(descriptors[0],flags));
    CHECK(fcntl(descriptors[0],F_GETFL)==original);
    CHECK(obd_io_nonblock(descriptors[0],0)<0);
    CHECK(fcntl(descriptors[0],F_GETFL)==original);
    int alias=dup(descriptors[0]),second_flags;
    CHECK(alias>=0);
    CHECK(!obd_io_nonblock(descriptors[0],&flags));
    CHECK(!obd_io_nonblock(alias,&second_flags));
    CHECK(second_flags&O_NONBLOCK);
    CHECK(!obd_io_restore_pair(descriptors[0],flags,alias,second_flags));
    CHECK(fcntl(descriptors[0],F_GETFL)==original);
    CHECK(fcntl(alias,F_GETFL)==original);
    CHECK(!obd_io_nonblock(alias,&flags));
    CHECK(obd_io_restore_pair(alias,flags,-1,0)<0);
    CHECK(fcntl(alias,F_GETFL)==original);
    close(alias);
    close(descriptors[0]);
    CHECK(obd_io_restore(-1,flags)<0 && obd_io_nonblock(-1,&flags)<0);
}
int main(void) {
    CHECK(sizeof(obd_pollfd)==8);
    test_model(); test_platform();
    puts("nonblocking I/O: partial transfer, EINTR/EAGAIN, cancellation, timeout, EOF, flag restore passed");
    return 0;
}
