#include "inspect.h"
#include <string.h>
int obd_platform_inspect(const obd_media_api *a, obd_inspect_result *r) {
    if(!r) return -1;
    memset(r,0,sizeof(*r)); r->stage=1;
    if(!a || !a->screen_create_context || !a->screen_destroy_context ||
       !a->screen_get_context_property_iv || !a->screen_get_context_property_pv ||
       !a->screen_get_display_property_iv || !a->mmr_connect || !a->mmr_disconnect) return -1;
    void *screen=0,*renderer=0,*displays[8]={0}; int count=0,ok=0;
    r->stage=2;
    if(a->screen_create_context(&screen,0) || !screen) goto done;
    r->stage=3;
    if(a->screen_get_context_property_iv(screen,59,&count) || count<1 || count>8 ||
       a->screen_get_context_property_pv(screen,60,displays)) goto done;
    r->stage=4;
    for(int i=0;i<count;i++) {
        int size[2]={0},id=-1;
        if(!displays[i] || a->screen_get_display_property_iv(displays[i],87,&id) || id<0 ||
           a->screen_get_display_property_iv(displays[i],40,size) ||
           size[0]<1 || size[0]>16384 || size[1]<1 || size[1]>16384) goto done;
        for(int j=0;j<i;j++) if(r->displays[j].id==id) goto done;
        r->displays[i]=(obd_inspect_display){id,size[0],size[1]}; r->count=i+1;
    }
    r->stage=5; renderer=a->mmr_connect(0);
    if(!renderer) goto done;
    r->renderer_connected=1; ok=1; r->stage=0;
done:
    if(renderer) a->mmr_disconnect(renderer);
    if(screen && a->screen_destroy_context(screen)) r->cleanup_failed=1;
    return ok && !r->cleanup_failed?0:-1;
}
