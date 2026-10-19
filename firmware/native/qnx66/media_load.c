#include "media.h"
#include <string.h>
extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);
extern int dlclose(void *);
void obd_media_unload(void *libraries[4]) {
    for (int i=3;i>=0;i--) if(libraries[i]) { dlclose(libraries[i]); libraries[i]=0; }
}
int obd_media_load(obd_media_api *a, void *libraries[4]) {
    obd_media_load_error error;
    return obd_media_load_diagnostic(a,libraries,&error);
}
int obd_media_load_diagnostic(obd_media_api *a, void *libraries[4], obd_media_load_error *error) {
    if(error) { error->library=-1; error->symbol=0; error->cleanup_failed=0; }
    if (!a || !libraries) return -1;
    memset(a,0,sizeof(*a)); memset(libraries,0,4*sizeof(void *));
    const char *paths[4]={"/base/usr/lib/libscreen.so.1","/base/usr/lib/libmmrndclient.so.1","/base/usr/lib/libstrm.so.1",0};
    for(int i=0;i<4;i++) {
        libraries[i]=dlopen(paths[i],2);
        if(!libraries[i]) { if(error) error->library=i; goto fail; }
    }
/* POSIX dlsym representation; compile-time checked, without incompatible casts. */
#define LOAD(lib,field) do { \
    void *symbol=dlsym(libraries[lib],#field); \
    typedef char same_size[(sizeof(a->field)==sizeof(symbol))?1:-1]; \
    (void)sizeof(same_size); \
    if(!symbol) { if(error) { error->library=lib; error->symbol=#field; } goto fail; } \
    memcpy(&a->field,&symbol,sizeof(symbol)); \
} while(0)
    LOAD(0,screen_create_context);
    LOAD(0,screen_destroy_context);
    LOAD(0,screen_get_context_property_iv);
    LOAD(0,screen_get_context_property_pv);
    LOAD(0,screen_get_display_property_iv);
    LOAD(0,screen_create_window_type);
    LOAD(0,screen_destroy_window);
    LOAD(0,screen_create_window_group);
    LOAD(0,screen_create_window_buffers);
    LOAD(0,screen_get_window_property_iv);
    LOAD(0,screen_get_window_property_pv);
    LOAD(0,screen_get_window_property_cv);
    LOAD(0,screen_set_window_property_iv);
    LOAD(0,screen_set_window_property_pv);
    LOAD(0,screen_get_buffer_property_iv);
    LOAD(0,screen_get_buffer_property_pv);
    LOAD(0,screen_post_window);
    LOAD(0,screen_flush_context);
    LOAD(0,screen_create_event);
    LOAD(0,screen_destroy_event);
    LOAD(0,screen_get_event);
    LOAD(0,screen_get_event_property_iv);
    LOAD(0,screen_get_event_property_pv);
    LOAD(1,mmr_connect);
    LOAD(1,mmr_disconnect);
    LOAD(1,mmr_context_create);
    LOAD(1,mmr_context_destroy);
    LOAD(1,mmr_context_parameters);
    LOAD(1,mmr_output_attach);
    LOAD(1,mmr_output_parameters);
    LOAD(1,mmr_output_detach);
    LOAD(1,mmr_input_parameters);
    LOAD(1,mmr_input_attach);
    LOAD(1,mmr_input_detach);
    LOAD(1,mmr_play);
    LOAD(1,mmr_stop);
    LOAD(2,strm_dict_new);
    LOAD(2,strm_dict_set);
    LOAD(2,strm_dict_destroy);
    LOAD(3,name_open);
    LOAD(3,name_close);
    LOAD(3,TimerTimeout);
    LOAD(3,MsgSendvs_r);
#undef LOAD
    return 0;
fail:
    for(int i=3;i>=0;i--) if(libraries[i]) {
        if(dlclose(libraries[i]) && error) error->cleanup_failed=1;
        libraries[i]=0;
    }
    memset(a,0,sizeof(*a)); return -1;
}
