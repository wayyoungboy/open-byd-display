#include "network.h"
#include "io.h"
#include <stdint.h>
#include <string.h>
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
int obd_video_accept(const char *address, unsigned port, void **library, const volatile int *cancel) {
    if(!library) return -1;
    *library=0;
    unsigned char local[16];
    if(obd_video_ipv4(address,port,local)) return -1;
    int (*socket_fn)(int,int,int)=0;
    int (*bind_fn)(int,const void *,unsigned)=0;
    int (*listen_fn)(int,int)=0;
    int (*accept_fn)(int,void *,unsigned *)=0;
    void *handle=dlopen("/base/lib/libsocket.so.3",2);
    if(!handle) return -1;
#define LOAD(field,name) do { void *p=dlsym(handle,name); \
    typedef char same_size[sizeof(field)==sizeof(p)?1:-1]; (void)sizeof(same_size); \
    if(!p) goto failed; memcpy(&field,&p,sizeof(p)); } while(0)
    LOAD(socket_fn,"socket"); LOAD(bind_fn,"bind");
    LOAD(listen_fn,"listen"); LOAD(accept_fn,"accept");
#undef LOAD
    obd_io io; obd_io_platform(&io.api); io.cancel=cancel;
    io.deadline=io.api.now();
    if(!io.deadline) goto failed;
    io.deadline+=30000;
    int fd=socket_fn(2,1,0), client=-1,flags;
    if(fd<0) goto failed;
    if(!obd_io_nonblock(fd,&flags) && !bind_fn(fd,local,sizeof(local)) && !listen_fn(fd,1)) {
        while(!obd_io_wait(&io,fd,1)) {
            client=accept_fn(fd,0,0);
            if(client>=0) break;
            if(!obd_io_retry(&io)) break;
        }
    }
    close(fd);
    if(client<0) goto failed;
    if(obd_io_nonblock(client,&flags)) { close(client); goto failed; }
    *library=handle;
    return client;
failed:
    dlclose(handle); return -1;
}
void obd_video_network_close(void *library) { if(library) dlclose(library); }
