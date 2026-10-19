#include "stream.h"
#include "network.h"
#include "io.h"
#include "baseline_platform.h"
#ifdef OBD_WITH_SUPERVISION
#include "guard.h"
#endif
#include <string.h>
extern int getpid(void);
extern int dup2(int,int);
extern int close(int);
extern int snprintf(char *,size_t,const char *,...);
extern int write(int,const void *,unsigned);
extern void (*signal(int,void (*)(int)))(int);
static volatile int stopped;
static unsigned session_number;
static void stop(int number) { if(number!=13) stopped=1; }
static int number(const char *s,int *out) {
    unsigned value=0; int negative=0;
    if(*s=='-') { negative=1; ++s; }
    if(!*s) return -1;
    while(*s) {
        if(*s<'0' || *s>'9' || value>10000) return -1;
        value=value*10+(unsigned)(*s++-'0');
    }
    *out=negative?-(int)value:(int)value; return 0;
}
static int play(int argc,char **argv,int continuous) {
    if(argc!=10) return 64;
    int values[8];
    for(int i=0;i<8;i++) if(number(argv[i+2],&values[i])) return 64;
    char session[33];
    int n=snprintf(session,sizeof(session),"%s%u%u",argv[1],(unsigned)getpid(),++session_number);
    if(n<0 || (size_t)n>=sizeof(session)) return 64;
    obd_media_config config={session,values[0],values[1],values[2],values[3],values[4],values[5],values[6],255,values[7],0,0,0};
    obd_media_api api; void *libraries[4];
    obd_media_load_error load_error;
    if(obd_media_load_diagnostic(&api,libraries,&load_error)) {
        char text[384];
        int count=snprintf(text,sizeof(text),"{\"event\":\"library_load_failed\",\"library_index\":%d,\"missing_symbol\":\"%s\",\"cleanup_confirmed\":%s}\n",load_error.library,load_error.symbol?load_error.symbol:"",load_error.cleanup_failed?"false":"true");
        if(count>0 && (size_t)count<sizeof(text)) (void)write(2,text,(unsigned)count);
        return load_error.cleanup_failed?75:69;
    }
    int input_flags,output_flags,result;
    if(obd_io_nonblock(0,&input_flags)) { obd_media_unload(libraries); return 71; }
    if(obd_io_nonblock(1,&output_flags)) {
        (void)obd_io_restore(0,input_flags); obd_media_unload(libraries); return 71;
    }
    obd_media media;
    result=obd_stream_run(&media,&api,&config,0,1,continuous,&stopped);
    if(obd_io_restore_pair(0,input_flags,1,output_flags)) result=75;
    obd_media_unload(libraries);
    return result;
}
static int player_main(int argc,char **argv) {
    if(signal(2,stop)==(void (*)(int))-1 || signal(15,stop)==(void (*)(int))-1 ||
        signal(13,stop)==(void (*)(int))-1) return 71;
    if(argc==2 && !strcmp(argv[1],"--identity")) return obd_baseline_identity();
    if(argc==2 && !strcmp(argv[1],"--baseline-check")) return obd_baseline_platform_check(&stopped);
    if(argc==13 && (!strcmp(argv[1],"--tcp") || !strcmp(argv[1],"--serve") || !strcmp(argv[1],"--baseline-serve"))) {
        int bound=!strcmp(argv[1],"--baseline-serve");
        int port,continuous=strcmp(argv[1],"--tcp")!=0;
        if(number(argv[3],&port) || port<1024 || port>65535) return 64;
        if(bound) {
            if(port!=47792) return 64;
            int checked=obd_baseline_platform_check(&stopped);
            if(checked) return checked;
        }
        obd_video_listener listener;
        if(obd_video_listen(argv[2],(unsigned)port,&listener)) return 72;
        if(listener.fd<=2) { obd_video_listener_close(&listener); return 72; }
        int result=0;
        do {
            int fd=obd_video_next(&listener,&stopped);
            if(fd==OBD_IO_CANCELLED) break;
            if(fd==OBD_IO_TIMEOUT && continuous) continue;
            if(fd<0) { result=72; break; }
            if(dup2(fd,0)<0 || dup2(fd,1)<0) { close(fd); close(0); close(1); result=72; break; }
            if(fd>1) close(fd);
            char *arguments[11]; arguments[0]=argv[0];
            for(int i=1;i<10;i++) arguments[i]=argv[i+3];
            arguments[10]=0;
            result=play(10,arguments,continuous);
            close(0); close(1);
            if(continuous && result) {
                char line[96];
                int n=snprintf(line,sizeof(line),"{\"event\":\"session_failed\",\"exit_code\":%d}\n",result);
                if(n>0 && (size_t)n<sizeof(line)) (void)write(2,line,(unsigned)n);
            }
            if(result==75) break; /* Do not re-admit after unconfirmed resource cleanup. */
        } while(continuous && !stopped);
        obd_video_listener_close(&listener);
        return stopped?0:result;
    }
    return play(argc,argv,0);
}
int main(int argc,char **argv) {
#ifdef OBD_WITH_SUPERVISION
    int supervised=0;
    if(argc==16 && !strcmp(argv[1],"--baseline-serve") && !strcmp(argv[13],"--supervised")) {
        int lease,health;
        if(number(argv[14],&lease) || number(argv[15],&health) || lease>1023 || health>1023 ||
           obd_guard_start(lease,health)) return 64;
        obd_io_watch(obd_guard_tick); obd_stream_observer(obd_guard_media); supervised=1; argc=13;
    }
#endif
    int result=player_main(argc,argv);
#ifdef OBD_WITH_SUPERVISION
    if(supervised) { obd_stream_observer(0); obd_io_watch(0); obd_guard_stop(); }
#endif
    return result;
}
