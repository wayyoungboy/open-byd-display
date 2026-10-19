#include "media.h"
#include "network.h"
#include "io.h"
#include "stream.h"
#include <stdio.h>
#include <stdlib.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"gate failed line %d: %s\n",__LINE__,#x); exit(1); } } while(0)
extern int obd_test_player_main(int,char **);
static unsigned checks,listens,loads,identities;
static int baseline_result=78;
int obd_baseline_identity(void) { identities++; return 0; }
int obd_baseline_platform_check(const volatile int *cancel) { CHECK(cancel && !*cancel); checks++; return baseline_result; }
int obd_video_listen(const char *s,unsigned port,obd_video_listener *v) { (void)s; (void)port; (void)v; listens++; return -1; }
int obd_video_next(obd_video_listener *l,const volatile int *c) { (void)l; (void)c; CHECK(0); return -1; }
void obd_video_listener_close(obd_video_listener *l) { (void)l; CHECK(0); }
int obd_media_load_diagnostic(obd_media_api *a,void *p[4],obd_media_load_error *e) { (void)a; (void)p; (void)e; loads++; return -1; }
void obd_media_unload(void *p[4]) { (void)p; CHECK(0); }
int obd_io_nonblock(int fd,int *v) { (void)fd; (void)v; CHECK(0); return -1; }
int obd_io_restore(int fd,int v) { (void)fd; (void)v; CHECK(0); return -1; }
int obd_io_restore_pair(int a,int b,int c,int d) { (void)a; (void)b; (void)c; (void)d; CHECK(0); return -1; }
int obd_stream_run(obd_media *m,const obd_media_api *a,const obd_media_config *c,int i,int o,int continuous,const volatile int *cancel) {
    (void)m; (void)a; (void)c; (void)i; (void)o; (void)continuous; (void)cancel; CHECK(0); return -1;
}
int main(void) {
    char *args[]={"player","--baseline-serve","192.168.77.1","47792","TEST","2","1920","720","0","0","1920","720","3",0};
    CHECK(obd_test_player_main(13,args)==78); CHECK(checks==1 && listens==0 && loads==0);
    baseline_result=0; CHECK(obd_test_player_main(13,args)==72); CHECK(checks==2 && listens==1 && loads==0);
    args[3]="47782"; CHECK(obd_test_player_main(13,args)==64); CHECK(checks==2 && listens==1);
    args[1]="--identity"; CHECK(obd_test_player_main(2,args)==0); CHECK(identities==1 && checks==2 && listens==1);
    args[1]="--baseline-check"; CHECK(obd_test_player_main(2,args)==0); CHECK(checks==3 && listens==1 && loads==0);
    args[1]="--serve"; CHECK(obd_test_player_main(13,args)==72); CHECK(checks==3 && listens==2 && loads==0);
    puts("baseline gate precedes network/media; no actual network or platform calls"); return 0;
}
