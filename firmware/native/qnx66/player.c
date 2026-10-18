#include "media.h"
#include "network.h"
#include "io.h"
#include <string.h>
extern void *malloc(size_t);
extern void free(void *);
extern int getpid(void);
extern int dup2(int,int);
extern int close(int);
static obd_io io;
extern int snprintf(char *, size_t, const char *, ...);
extern void (*signal(int, void (*)(int)))(int);
static volatile int stopped;
static void stop(int number) { (void)number; stopped=1; }
extern int poll(obd_pollfd *, unsigned, int);

static int number(const char *s, int *out) {
    unsigned value=0; int negative=0;
    if (*s=='-') { negative=1; ++s; }
    if (!*s) return -1;
    while (*s) {
        if (*s<'0'||*s>'9'||value>10000) return -1;
        value=value*10+(unsigned)(*s++-'0');
    }
    *out=negative?-(int)value:(int)value; return 0;
}
static int exact(void *data, unsigned bytes) {
    return obd_io_exact(&io,0,data,bytes,0);
}
static uint32_t be32(const uint8_t *p) {
    return (uint32_t)p[0]<<24 | (uint32_t)p[1]<<16 | (uint32_t)p[2]<<8 | p[3];
}
static int report(const char *stage, const obd_media *m) {
    char line[384];
    char ack[96]={0};
    if(!strcmp(stage,"decoder_submitted"))
        snprintf(ack,sizeof(ack),",\"ack\":%u,\"accepted_sequence\":%u",m->sequence.sequence,m->sequence.sequence);
    int done=!strcmp(stage,"done");
    int n=snprintf(line,sizeof(line),"{\"ok\":true,\"stage\":\"%s\",\"ready\":%s,\"protocol\":\"LHF2\",\"done\":%s,\"cleanup\":%s,\"submitted\":%u,\"screen_posts\":%u,\"property_readback\":%s,\"visual_verified\":false%s}\n",stage,!strcmp(stage,"ready")?"true":"false",done?"true":"false",done?"true":"false",m->submitted,m->posts,m->readback?"true":"false",ack);
    if(n<0 || (size_t)n>=sizeof(line)) return -1;
    return obd_io_exact(&io,1,line,(unsigned)n,1);
}
static int run_stream(int argc, char **argv) {
    /* Explicit operator geometry; BVL1/LHF2 arrives on stdin, no network listener. */
    if(argc!=10) return 64;
    io.deadline=io.api.now();
    if(!io.deadline) return 71;
    io.deadline+=30000;
    if(signal(2,stop)==(void (*)(int))-1 || signal(15,stop)==(void (*)(int))-1 ||
        signal(13,stop)==(void (*)(int))-1) return 71;
    int values[8];
    for(int i=0;i<8;i++) if(number(argv[i+2],&values[i])) return 64;
    uint8_t greeting[39];
    if(exact(greeting,sizeof(greeting)) || memcmp(greeting,"BVL1\0\0\0\17{\"op\":\"stream\"}",23) || be32(greeting+35)) return 65;
    char session[33];
    int n=snprintf(session,sizeof(session),"%s%u",argv[1],(unsigned)getpid());
    if(n<0 || (size_t)n>=sizeof(session)) return 64;
    obd_media_config config={session,values[0],values[1],values[2],values[3],values[4],values[5],values[6],255,values[7],be32(greeting+23),be32(greeting+27),be32(greeting+31)};
    obd_media_api api; void *libraries[4];
    if(obd_media_load(&api,libraries)) return 69;
    obd_media media;
    if(obd_media_open(&media,&api,&config)) { obd_media_unload(libraries); return 70; }
    uint8_t *payload=malloc(OBD_LHF2_MAX_PAYLOAD);
    int result=74,ended=0;
    if(!payload || report("ready",&media)) goto done;
    for(unsigned i=0;i<=60;i++) {
        uint8_t header[24]; obd_lhf2_frame frame;
        if(exact(header,sizeof(header)) || obd_lhf2_header(header,sizeof(header),&frame)) goto done;
        if(!frame.bytes) { ended=1; break; }
        if(i==60 || exact(payload,frame.bytes) || obd_media_submit(&media,&frame,payload,frame.bytes) ||
            obd_media_events(&media) || report("decoder_submitted",&media)) goto done;
    }
    if(!ended || !media.submitted) goto done;
    /* Bounded drain for asynchronous child/post events. */
    for(unsigned i=0;i<20;i++) {
        if(stopped) goto done;
        if(obd_media_events(&media)) goto done;
        if(poll(0,0,100)<0) goto done;
    }
    if(!media.child || !media.readback || !media.posts) goto done;
    result=0;
done:
    if(obd_media_close(&media)) result=75;
    if(!result && report("done",&media)) result=74;
    free(payload); obd_media_unload(libraries); return result;
}
static int play(int argc,char **argv) {
    if(argc!=10) return 64;
    int input_flags,output_flags;
    obd_io_platform(&io.api); io.cancel=&stopped;
    if(obd_io_nonblock(0,&input_flags)) return 71;
    if(obd_io_nonblock(1,&output_flags)) { obd_io_restore(0,input_flags); return 71; }
    int result=run_stream(argc,argv);
    if(obd_io_restore_pair(0,input_flags,1,output_flags)) result=75;
    return result;
}
int main(int argc, char **argv) {
    if(argc==13 && !strcmp(argv[1],"--tcp")) {
        int port;
        if(number(argv[3],&port) || port<1024 || port>65535) return 64;
        if(signal(2,stop)==(void (*)(int))-1 || signal(15,stop)==(void (*)(int))-1 ||
            signal(13,stop)==(void (*)(int))-1) return 71;
        void *library=0;
        int fd=obd_video_accept(argv[2],(unsigned)port,&library,&stopped);
        if(fd<0) return 72;
        if(dup2(fd,0)<0 || dup2(fd,1)<0) {
            close(fd); obd_video_network_close(library); return 72;
        }
        if(fd>1) close(fd);
        char *arguments[11]; arguments[0]=argv[0];
        for(int i=1;i<10;i++) arguments[i]=argv[i+3];
        arguments[10]=0;
        int result=play(10,arguments);
        close(0); close(1); obd_video_network_close(library);
        return result;
    }
    return play(argc,argv);
}
