#include "inspect.h"
#include <string.h>
extern int snprintf(char *,size_t,const char *,...);
extern int write(int,const void *,unsigned);
extern int dlclose(void *);
#ifndef OBD_TARGET_ID
#error OBD_TARGET_ID required
#endif
static int emit(const char *p,unsigned n) {
    while(n) { int k=write(1,p,n); if(k<=0 || (unsigned)k>n) return -1; p+=k; n-=(unsigned)k; }
    return 0;
}
int main(int argc,char **argv) {
    if(argc!=2 || strcmp(argv[1],"--platform")) return 64;
    obd_media_api api; void *libraries[4]; obd_media_load_error error;
    if(obd_media_load_diagnostic(&api,libraries,&error)) {
        char text[512];
        int n=snprintf(text,sizeof(text),"{\"probe\":\"OpenBYDDisplay.platform\",\"target\":\"" OBD_TARGET_ID "\",\"ok\":false,\"stage\":\"library_load\",\"library_index\":%d,\"missing_symbol\":\"%s\",\"cleanup_confirmed\":%s,\"hardware_verified\":false,\"production_flashable\":false}\n",error.library,error.symbol?error.symbol:"",error.cleanup_failed?"false":"true");
        if(n<0 || (size_t)n>=sizeof(text) || emit(text,(unsigned)n)) return 74;
        return 69;
    }
    obd_inspect_result r; int rc=obd_platform_inspect(&api,&r);
    for(int i=3;i>=0;i--) if(libraries[i] && dlclose(libraries[i])) r.cleanup_failed=1;
    char text[2048]; int used=snprintf(text,sizeof(text),"{\"probe\":\"OpenBYDDisplay.platform\",\"target\":\"" OBD_TARGET_ID "\",\"ok\":%s,\"stage\":%d,\"cleanup_confirmed\":%s,\"renderer_connected\":%s,\"hardware_verified\":false,\"qnx_decode_verified\":false,\"production_flashable\":false,\"displays\":[",!rc&&!r.cleanup_failed?"true":"false",r.stage,r.cleanup_failed?"false":"true",r.renderer_connected?"true":"false");
    if(used<0 || (size_t)used>=sizeof(text)) return 74;
    for(int i=0;i<r.count;i++) {
        int n=snprintf(text+used,sizeof(text)-(unsigned)used,"%s{\"id\":%d,\"width\":%d,\"height\":%d}",i?",":"",r.displays[i].id,r.displays[i].width,r.displays[i].height);
        if(n<0 || (size_t)n>=sizeof(text)-(unsigned)used) return 74;
        used+=n;
    }
    if((unsigned)used+4>sizeof(text)) return 74;
    memcpy(text+used,"]}\n",3); used+=3;
    if(emit(text,(unsigned)used)) return 74;
    return rc || r.cleanup_failed?70:0;
}
