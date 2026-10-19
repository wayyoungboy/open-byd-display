#include "inspect.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"%d: %s\n",__LINE__,#x); exit(1); } } while(0)
static int fail,count,closed,disconnected,duplicate,cleanup_bad;
static int create(void **p,int flags) { CHECK(flags==0); *p=(void *)1; return fail==2?-1:0; }
static int destroy(void *p) { CHECK(p==(void *)1); closed++; return cleanup_bad; }
static int getiv(void *p,int prop,int *n) { CHECK(p==(void *)1 && prop==59); *n=count; return fail==3?-1:0; }
static int getpv(void *p,int prop,void **list) {
    CHECK(p==(void *)1 && prop==60 && count<=8);
    for(int i=0;i<count;i++) list[i]=(void *)(uintptr_t)(i+10);
    return 0;
}
static int display(void *p,int prop,int *v) {
    if(fail==4) return -1;
    if(prop==87) *v=duplicate?1:(int)(uintptr_t)p-10;
    else { CHECK(prop==40); v[0]=1920; v[1]=720; }
    return 0;
}
static void *connect(const char *path) { CHECK(!path); return fail==5?0:(void *)2; }
static void disconnect(void *p) { CHECK(p==(void *)2); disconnected++; }
int main(void) {
    obd_media_api a; memset(&a,0,sizeof(a));
    a.screen_create_context=create; a.screen_destroy_context=destroy;
    a.screen_get_context_property_iv=getiv; a.screen_get_context_property_pv=getpv;
    a.screen_get_display_property_iv=display; a.mmr_connect=connect; a.mmr_disconnect=disconnect;
    obd_inspect_result r;
    for(fail=0;fail<=5;fail++) {
        count=2; closed=disconnected=0;
        int rc=obd_platform_inspect(&a,&r);
        CHECK(closed==1); CHECK((rc==0)==(fail<2));
        if(fail<2) CHECK(r.count==2 && r.displays[1].id==1 && r.displays[0].width==1920 && disconnected==1);
        else CHECK(r.stage==fail && !r.renderer_connected);
    }
    fail=0; count=9; closed=0;
    CHECK(obd_platform_inspect(&a,&r) && r.stage==3 && closed==1);
    count=2; duplicate=1;
    CHECK(obd_platform_inspect(&a,&r) && r.stage==4);
    duplicate=0; cleanup_bad=1;
    CHECK(obd_platform_inspect(&a,&r) && r.cleanup_failed && r.renderer_connected);
    CHECK(obd_platform_inspect(0,&r) && r.stage==1);
    return 0;
}
