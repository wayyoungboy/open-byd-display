#ifdef OBD_IO_HOST
#define _POSIX_C_SOURCE 200809L
#endif
#include "io.h"
#ifdef OBD_IO_HOST
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <time.h>
#include <unistd.h>
static int io_error(void) { return errno; }
static int io_read(int fd,void *p,unsigned n) { return (int)read(fd,p,n); }
static int io_write(int fd,const void *p,unsigned n) { return (int)write(fd,p,n); }
static int io_poll(obd_pollfd *p,unsigned n,int timeout) { return poll((struct pollfd *)p,n,timeout); }
static uint64_t io_now(void) {
    struct timespec t;
    if(clock_gettime(CLOCK_MONOTONIC,&t)) return 0;
    return (uint64_t)t.tv_sec*1000+(uint64_t)t.tv_nsec/1000000;
}
#else
extern int read(int,void *,unsigned);
extern int write(int,const void *,unsigned);
extern int poll(obd_pollfd *,unsigned,int);
extern int fcntl(int,int,...);
extern int *__get_errno_ptr(void);
typedef struct { int32_t seconds,nanoseconds; } obd_timespec;
extern int clock_gettime(int,obd_timespec *);
enum { F_GETFL=3, F_SETFL=4, O_NONBLOCK=0x80, EINTR=4, EAGAIN=11 };
static int io_error(void) { return *__get_errno_ptr(); }
static int io_read(int fd,void *p,unsigned n) { return read(fd,p,n); }
static int io_write(int fd,const void *p,unsigned n) { return write(fd,p,n); }
static int io_poll(obd_pollfd *p,unsigned n,int timeout) { return poll(p,n,timeout); }
static uint64_t io_now(void) {
    obd_timespec t;
    if(clock_gettime(2,&t) || t.seconds<0 || t.nanoseconds<0 || t.nanoseconds>=1000000000) return 0;
    return (uint64_t)(uint32_t)t.seconds*1000+(uint32_t)t.nanoseconds/1000000;
}
#endif
static int (*watch_tick)(void);
void obd_io_watch(int (*tick)(void)) { watch_tick=tick; }
void obd_io_platform(obd_io_api *api) {
    api->read=io_read; api->write=io_write; api->poll=io_poll; api->error=io_error; api->now=io_now;
}
int obd_io_nonblock(int fd,int *previous) {
    int flags=fcntl(fd,F_GETFL);
    if(flags<0 || !previous || fcntl(fd,F_SETFL,flags|O_NONBLOCK)<0) return -1;
    *previous=flags; return 0;
}
int obd_io_restore(int fd,int previous) { return fcntl(fd,F_SETFL,previous)<0?-1:0; }
int obd_io_restore_pair(int first_fd,int first_flags,int second_fd,int second_flags) {
    /* Reverse the mutations when descriptors share an open file description. */
    int second=obd_io_restore(second_fd,second_flags);
    int first=obd_io_restore(first_fd,first_flags);
    return first || second ? -1 : 0;
}
int obd_io_retry(obd_io *io) {
    int error=io->api.error();
    return error==EINTR || error==EAGAIN;
}
int obd_io_wait(obd_io *io,int fd,short events) {
    for(;;) {
        if(watch_tick && watch_tick()) return OBD_IO_CANCELLED;
        uint64_t now=io->api.now();
        if(io->cancel && *io->cancel) return OBD_IO_CANCELLED;
        if(!now) return OBD_IO_FAILED;
        if(now>=io->deadline) return OBD_IO_TIMEOUT;
        int timeout=io->deadline-now>5000?5000:(int)(io->deadline-now);
        if(watch_tick && timeout>250) timeout=250;
        obd_pollfd p={fd,events,0};
        int result=io->api.poll(&p,1,timeout);
        if(watch_tick && watch_tick()) return OBD_IO_CANCELLED;
        if(io->cancel && *io->cancel) return OBD_IO_CANCELLED;
        if(result<0) { if(io->api.error()==EINTR) continue; return -1; }
        if(!result) { if(watch_tick) continue; return OBD_IO_TIMEOUT; }
        if(p.revents&0x28) return OBD_IO_FAILED;
        if(p.revents&events) return 0;
        if(p.revents&0x10) return events==1?0:-1; /* Drain read bytes before EOF. */
    }
}
int obd_io_exact(obd_io *io,int fd,void *data,unsigned bytes,int writing) {
    unsigned pos=0;
    while(pos<bytes) {
        if(obd_io_wait(io,fd,writing?4:1)) return -1;
        int count=writing ? io->api.write(fd,(uint8_t *)data+pos,bytes-pos) : io->api.read(fd,(uint8_t *)data+pos,bytes-pos);
        if(count<0) { if(obd_io_retry(io)) continue; return -1; }
        if(!count || (unsigned)count>bytes-pos) return -1;
        pos+=(unsigned)count;
    }
    return 0;
}
