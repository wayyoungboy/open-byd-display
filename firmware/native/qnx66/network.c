#include "network.h"
#include "io.h"
#include <stdint.h>
#include <string.h>
#ifdef OBD_IO_HOST
#include <sys/socket.h>
#include <netinet/in.h>
#endif
extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);
extern int dlclose(void *);
extern int close(int);

/* QNX 6.6 BSD IPv4 layout. This is deliberately not Linux sockaddr_in. */
typedef struct {
    uint8_t length,family;
    uint8_t port[2],address[4],reserved[8];
} obd_ipv4;

static int private_address(const char *text, uint8_t out[4]) {
    if(!text) return -1;
    for(unsigned i=0;i<4;i++) {
        unsigned value=0,digits=0;
        while(*text>='0' && *text<='9') {
            if(++digits>3) return -1;
            value=value*10+(unsigned)(*text++-'0');
        }
        if(!digits || value>255 || (i<3 ? *text++!='.' : *text!=0)) return -1;
        out[i]=(uint8_t)value;
    }
    return out[0]==127 || out[0]==10 || (out[0]==192 && out[1]==168) ||
        (out[0]==172 && out[1]>=16 && out[1]<=31) ? 0 : -1;
}
int obd_video_ipv4(const char *address, unsigned port, unsigned char bytes[16]) {
    obd_ipv4 local; memset(&local,0,sizeof(local));
    if(!bytes || port<1024 || port>65535 || private_address(address,local.address)) return -1;
    local.length=sizeof(local); local.family=2;
    local.port[0]=(uint8_t)(port>>8); local.port[1]=(uint8_t)port;
    memcpy(bytes,&local,sizeof(local));
    return 0;
}
int obd_video_listen(const char *address,unsigned port,obd_video_listener *listener) {
    if(!listener) return -1;
    memset(listener,0,sizeof(*listener)); listener->fd=-1;
    unsigned char local[16];
    if(obd_video_ipv4(address,port,local)) {
#ifdef OBD_IO_HOST
        if(port || obd_video_ipv4(address,1024,local)) return -1;
        local[2]=local[3]=0; /* Ephemeral port for host tests only. */
#else
        return -1;
#endif
    }
    int (*socket_fn)(int,int,int)=0;
    int (*bind_fn)(int,const void *,unsigned)=0;
    int (*listen_fn)(int,int)=0;
    int (*accept_fn)(int,void *,unsigned *)=0;
#ifdef OBD_IO_HOST
    void *handle=dlopen(0,2);
    struct sockaddr_in native; memset(&native,0,sizeof(native));
    native.sin_family=AF_INET;
#ifdef __APPLE__
    native.sin_len=sizeof(native);
#endif
    memcpy(&native.sin_port,local+2,2); memcpy(&native.sin_addr,local+4,4);
#else
    void *handle=dlopen("/base/lib/libsocket.so.3",2);
#endif
    if(!handle) return -1;
#define LOAD(field,name) do { void *p=dlsym(handle,name); \
    typedef char same_size[sizeof(field)==sizeof(p)?1:-1]; (void)sizeof(same_size); \
    if(!p) { goto failed; } \
    memcpy(&field,&p,sizeof(p)); } while(0)
    LOAD(socket_fn,"socket"); LOAD(bind_fn,"bind");
    LOAD(listen_fn,"listen"); LOAD(accept_fn,"accept");
#undef LOAD
    int flags;
    listener->fd=socket_fn(2,1,0);
    if(listener->fd<0 || obd_io_nonblock(listener->fd,&flags)) goto failed;
#ifdef OBD_IO_HOST
    if(bind_fn(listener->fd,&native,sizeof(native))) goto failed;
#else
    if(bind_fn(listener->fd,local,sizeof(local))) goto failed;
#endif
    if(listen_fn(listener->fd,1)) goto failed;
    listener->library=handle; listener->accept_fn=accept_fn;
    return 0;
failed:
    if(listener->fd>=0) close(listener->fd);
    listener->fd=-1;
    dlclose(handle); return -1;
}
int obd_video_next(obd_video_listener *listener,const volatile int *cancel) {
    if(!listener || listener->fd<0 || !listener->library || !listener->accept_fn) return OBD_IO_FAILED;
    obd_io io; obd_io_platform(&io.api); io.cancel=cancel;
    uint64_t now=io.api.now();
    if(!now) return OBD_IO_FAILED;
    io.deadline=now+30000;
    for(;;) {
        int status=obd_io_wait(&io,listener->fd,1);
        if(status==OBD_IO_TIMEOUT && io.api.now()<io.deadline) continue;
        if(status) return status;
        int client=listener->accept_fn(listener->fd,0,0),flags;
        if(client<0) { if(obd_io_retry(&io)) continue; return OBD_IO_FAILED; }
        if(obd_io_nonblock(client,&flags)) { close(client); return OBD_IO_FAILED; }
        return client;
    }
}
void obd_video_listener_close(obd_video_listener *listener) {
    if(!listener) return;
    if(listener->fd>=0) close(listener->fd);
    if(listener->library) dlclose(listener->library);
    memset(listener,0,sizeof(*listener)); listener->fd=-1;
}
int obd_video_accept(const char *address,unsigned port,void **library,const volatile int *cancel) {
    if(!library) return -1;
    *library=0;
    obd_video_listener listener;
    if(obd_video_listen(address,port,&listener)) return -1;
    int fd=obd_video_next(&listener,cancel);
    if(fd<0) { obd_video_listener_close(&listener); return fd; }
    close(listener.fd); *library=listener.library;
    return fd;
}
void obd_video_network_close(void *library) { if(library) dlclose(library); }
