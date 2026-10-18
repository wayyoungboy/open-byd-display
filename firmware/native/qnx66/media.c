#include "media.h"
#include <string.h>
extern int snprintf(char *, size_t, const char *, ...);
enum { BUFFER_SIZE=5, DISPLAY=11, FORMAT=14, ALPHA=16, GROUP=18, ID_STRING=20,
       POINTER=34, POSITION=35, BUFFERS=37, SIZE=40, STRIDE=44, TRANSPARENCY=46,
       TYPE=47, USAGE=48, VISIBLE=51, WINDOW=52, ZORDER=54, SENSITIVITY=57,
       DISPLAY_COUNT=59, DISPLAYS=60, ID=87 };

static int valid(const obd_media_config *c) {
    if (!c || !c->session_id) return 0;
    size_t n=0;
    while (c->session_id[n]) {
        unsigned char x=(unsigned char)c->session_id[n++];
        if (n>32 || !((x>='a'&&x<='z')||(x>='A'&&x<='Z')||(x>='0'&&x<='9'))) return 0;
    }
    return n && c->display_id>=0 && c->physical_width>0 && c->physical_width<=1920 &&
        c->physical_height>0 && c->physical_height<=1080 && c->x>=0 && c->y>=0 &&
        c->width>0 && c->height>0 && c->width<=c->physical_width &&
        c->height<=c->physical_height && c->x<=c->physical_width-c->width &&
        c->y<=c->physical_height-c->height && c->opacity>=0 && c->opacity<=255 &&
        c->zorder>=-16 && c->zorder<=32 && c->encoded_width>=2 && c->encoded_width<=1920 &&
        c->encoded_height>=2 && c->encoded_height<=1080 && !(c->encoded_width&1) &&
        !(c->encoded_height&1) && c->fps>=1 && c->fps<=60;
}
static int set(obd_media *m, void *window, int property, int a, int b) {
    int values[2]={a,b};
    return m->api.screen_set_window_property_iv(window,property,values);
}
static int parameter(obd_media *m, int scope, const char *a, const char *v,
                     const char *b, const char *w) {
    void *d=m->api.strm_dict_new(), *next;
    if (!d) return -1;
    next=m->api.strm_dict_set(d,a,v);
    if (!next) { m->api.strm_dict_destroy(d); return -1; }
    d=next;
    if (b) {
        next=m->api.strm_dict_set(d,b,w);
        if (!next) { m->api.strm_dict_destroy(d); return -1; }
        d=next;
    }
    /* Each mmr call consumes the dictionary, including error returns. */
    if (scope==0) return m->api.mmr_context_parameters(m->renderer,d);
    if (scope==1) return m->api.mmr_output_parameters(m->renderer,(unsigned)m->output,d);
    return m->api.mmr_input_parameters(m->renderer,d);
}
static int send_message(obd_media *m, unsigned kind, const void *data, unsigned bytes) {
    uint32_t header[6]={0,0,0,0,kind,bytes};
    obd_iov vectors[2]={{header,sizeof(header)},{data,bytes}};
    uint64_t timeout=UINT64_C(300000000);
    /* Observed ARM32 QNX IPC: CLOCK_MONOTONIC=2, timeout SEND|REPLY=0x50. */
    if (m->api.TimerTimeout(2,0x50,0,&timeout,0)<0) return -1;
    int rc=m->api.MsgSendvs_r(m->channel,vectors,bytes?2:1,0,0);
    int cancelled=m->api.TimerTimeout(2,0x50,0,0,0);
    return rc<0 || cancelled<0 ? -1 : 0;
}
int obd_media_close(obd_media *m) {
    int error=0;
    if (!m) return -1;
    m->ready=m->readback=m->actual_visible=0;
    if (m->child && set(m,m->child,VISIBLE,0,0)) error=-1;
    if (m->parent && set(m,m->parent,VISIBLE,0,0)) error=-1;
    if (m->screen && m->api.screen_flush_context(m->screen,1)) error=-1;
    if (m->channel>=0) { if(m->api.name_close(m->channel)) error=-1; m->channel=-1; }
    if (m->playing) { if(m->api.mmr_stop(m->renderer)) error=-1; m->playing=0; }
    if (m->attached) { if(m->api.mmr_input_detach(m->renderer)) error=-1; m->attached=0; }
    if (m->output>=0) { if(m->api.mmr_output_detach(m->renderer,(unsigned)m->output)) error=-1; m->output=-1; }
    if (m->renderer) { if(m->api.mmr_context_destroy(m->renderer)) error=-1; m->renderer=0; }
    if (m->connection) { m->api.mmr_disconnect(m->connection); m->connection=0; }
    if (m->child) { if(m->api.screen_destroy_window(m->child)) error=-1; m->child=0; }
    if (m->parent) { if(m->api.screen_destroy_window(m->parent)) error=-1; m->parent=0; }
    if (m->event) { if(m->api.screen_destroy_event(m->event)) error=-1; m->event=0; }
    if (m->screen) { if(m->api.screen_destroy_context(m->screen)) error=-1; m->screen=0; }
    return error;
}
int obd_media_open(obd_media *m, const obd_media_api *a, const obd_media_config *c) {
    if (!m || !a) return -1;
    memset(m,0,sizeof(*m)); m->api=*a; m->output=m->channel=-1;
    if (!valid(c)) return -1;
#define NEED(field) if (!a->field) return -1
    NEED(screen_create_context); NEED(screen_destroy_context);
    NEED(screen_get_context_property_iv); NEED(screen_get_context_property_pv);
    NEED(screen_get_display_property_iv); NEED(screen_create_window_type);
    NEED(screen_destroy_window); NEED(screen_create_window_group);
    NEED(screen_create_window_buffers); NEED(screen_get_window_property_iv);
    NEED(screen_get_window_property_pv); NEED(screen_get_window_property_cv);
    NEED(screen_set_window_property_iv); NEED(screen_set_window_property_pv);
    NEED(screen_get_buffer_property_iv); NEED(screen_get_buffer_property_pv);
    NEED(screen_post_window); NEED(screen_flush_context);
    NEED(screen_create_event); NEED(screen_destroy_event); NEED(screen_get_event);
    NEED(screen_get_event_property_iv); NEED(screen_get_event_property_pv);
    NEED(mmr_connect); NEED(mmr_disconnect); NEED(mmr_context_create); NEED(mmr_context_destroy);
    NEED(mmr_context_parameters); NEED(mmr_output_attach); NEED(mmr_output_parameters);
    NEED(mmr_output_detach); NEED(mmr_input_parameters); NEED(mmr_input_attach);
    NEED(mmr_input_detach); NEED(mmr_play); NEED(mmr_stop);
    NEED(strm_dict_new); NEED(strm_dict_set); NEED(strm_dict_destroy);
    NEED(name_open); NEED(name_close); NEED(TimerTimeout); NEED(MsgSendvs_r);
#undef NEED
    m->config=*c;
    snprintf(m->group,sizeof(m->group),"OBD_G_%s",c->session_id);
    snprintf(m->video,sizeof(m->video),"OBD_V_%s",c->session_id);
    snprintf(m->channel_name,sizeof(m->channel_name),"obd_stream_%s",c->session_id);
    if (a->screen_create_context(&m->screen,0)) goto fail;
    int count=0,size[2],id; void *displays[8],*display=0;
    if (a->screen_get_context_property_iv(m->screen,DISPLAY_COUNT,&count) || count<1 || count>8 ||
        a->screen_get_context_property_pv(m->screen,DISPLAYS,displays)) goto fail;
    for (int i=0;i<count;i++) {
        if (a->screen_get_display_property_iv(displays[i],ID,&id)) goto fail;
        if (id==c->display_id) { if(display) goto fail; display=displays[i]; }
    }
    if (!display || a->screen_get_display_property_iv(display,SIZE,size) ||
        size[0]!=c->physical_width || size[1]!=c->physical_height) goto fail;
    if (a->screen_create_window_type(&m->parent,m->screen,0) ||
        a->screen_create_event(&m->event) || set(m,m->parent,VISIBLE,0,0) ||
        a->screen_set_window_property_pv(m->parent,DISPLAY,&display) ||
        a->screen_create_window_group(m->parent,m->group) ||
        set(m,m->parent,POSITION,0,0) || set(m,m->parent,SIZE,size[0],size[1]) ||
        set(m,m->parent,BUFFER_SIZE,size[0],size[1]) || set(m,m->parent,FORMAT,8,0) ||
        set(m,m->parent,USAGE,14,0) || set(m,m->parent,TRANSPARENCY,3,0) ||
        set(m,m->parent,SENSITIVITY,2,0) || set(m,m->parent,ALPHA,255,0) ||
        set(m,m->parent,ZORDER,c->zorder,0) || a->screen_create_window_buffers(m->parent,1)) goto fail;
    void *buffer=0,*pixels=0; int stride=0,buffer_size[2];
    if (a->screen_get_window_property_pv(m->parent,BUFFERS,&buffer) || !buffer ||
        a->screen_get_buffer_property_iv(buffer,STRIDE,&stride) ||
        a->screen_get_buffer_property_iv(buffer,BUFFER_SIZE,buffer_size) ||
        buffer_size[0]!=size[0] || buffer_size[1]!=size[1] || stride<size[0]*4 || stride>16384 ||
        a->screen_get_buffer_property_pv(buffer,POINTER,&pixels) || !pixels) goto fail;
    memset(pixels,0,(size_t)stride*(unsigned)size[1]);
    if (a->screen_post_window(m->parent,buffer,0,0,0)) goto fail;
    m->connection=a->mmr_connect(0);
    if (!m->connection) goto fail;
    m->renderer=a->mmr_context_create(m->connection,m->channel_name,0,0600);
    if (!m->renderer || parameter(m,0,"prefetch_mode","-1","mmf.SCREEN_WRITER_DISABLE_BACKGROUND","1")) goto fail;
    char url[384];
    int n=snprintf(url,sizeof(url),"screen:/any?wingrp=%s&winembed=1&winid=%s&nodstviewport=1&initflags=invisible",m->group,m->video);
    if (n<0 || (size_t)n>=sizeof(url)) goto fail;
    m->output=a->mmr_output_attach(m->renderer,url,"video");
    if (m->output<0 || parameter(m,1,"mmf.SCREEN_WRITER_DISABLE_MEDIACLOCK","1","mmf.SCREEN_WRITER_POST_BUFFER_NOSYNC","1") ||
        parameter(m,2,"VideoDecoderLowLatencyMode","1","ColorSpaceMode","0")) goto fail;
    n=snprintf(url,sizeof(url),"mdevpush:/dev/name/local/%s?fourcc=H264&video.frame_rate=%u&video.depth=24&video.width=%u&video.height=%u&queue_capacity=2",m->channel_name,c->fps,c->encoded_width,c->encoded_height);
    if (n<0 || (size_t)n>=sizeof(url) || a->mmr_input_attach(m->renderer,url,"track")) goto fail;
    m->attached=1;
    if (a->mmr_play(m->renderer)) goto fail;
    m->playing=1;
    uint64_t timeout=UINT64_C(300000000);
    if (a->TimerTimeout(2,0x50,0,&timeout,0)<0) goto fail;
    m->channel=a->name_open(m->channel_name,0);
    if (a->TimerTimeout(2,0x50,0,0,0)<0 || m->channel<0) goto fail;
    uint32_t format[8]={0,UINT32_C(0x48323634),c->encoded_width,c->encoded_height,24,c->fps,0,0};
    if (send_message(m,2,format,sizeof(format))) goto fail;
    m->ready=1;
    return 0;
fail:
    m->failed=1; obd_media_close(m); return -1;
}
static int readback(obd_media *m) {
    const obd_media_config *c=&m->config;
    int a[2],b[2],alpha,visible,z;
    m->readback=m->actual_visible=0;
    if (m->api.screen_get_window_property_iv(m->child,POSITION,a) ||
        m->api.screen_get_window_property_iv(m->child,SIZE,b) ||
        a[0]!=c->x || a[1]!=c->y || b[0]!=c->width || b[1]!=c->height ||
        m->api.screen_get_window_property_iv(m->child,ALPHA,&alpha) || alpha!=c->opacity ||
        m->api.screen_get_window_property_iv(m->child,VISIBLE,&visible) || visible!=1 ||
        m->api.screen_get_window_property_iv(m->child,ZORDER,&z) || z!=1 ||
        m->api.screen_get_window_property_iv(m->parent,POSITION,a) || a[0] || a[1] ||
        m->api.screen_get_window_property_iv(m->parent,SIZE,b) || b[0]!=c->physical_width || b[1]!=c->physical_height ||
        m->api.screen_get_window_property_iv(m->parent,ALPHA,&alpha) || alpha!=255 ||
        m->api.screen_get_window_property_iv(m->parent,VISIBLE,&visible) || visible!=1 ||
        m->api.screen_get_window_property_iv(m->parent,ZORDER,&z) || z!=c->zorder) return -1;
    m->readback=1; m->actual_visible=c->opacity>0;
    return 0;
}
static int geometry(obd_media *m) {
    const obd_media_config *c=&m->config;
    if (set(m,m->child,VISIBLE,0,0) || set(m,m->child,POSITION,c->x,c->y) ||
        set(m,m->child,SIZE,c->width,c->height) || set(m,m->child,ALPHA,c->opacity,0) ||
        set(m,m->child,ZORDER,1,0) || set(m,m->child,SENSITIVITY,2,0) ||
        set(m,m->child,VISIBLE,1,0) || set(m,m->parent,VISIBLE,1,0) ||
        m->api.screen_flush_context(m->screen,1)) return -1;
    return readback(m);
}
int obd_media_events(obd_media *m) {
    if (!m || !m->ready || m->failed) return -1;
    for (unsigned i=0;i<64;i++) {
        int type=0; void *window=0;
        if (m->api.screen_get_event(m->screen,m->event,0) ||
            m->api.screen_get_event_property_iv(m->event,TYPE,&type)) goto fail;
        if (!type) { if(m->child && readback(m)) goto fail; return 0; }
        if (type!=1 && type!=3 && type!=9) continue;
        if (m->api.screen_get_event_property_pv(m->event,WINDOW,&window) || !window) goto fail;
        if (type==1) {
            char id[64]={0},group[64]={0}; int kind=0;
            if (m->api.screen_get_window_property_cv(window,ID_STRING,sizeof(id),id) ||
                m->api.screen_get_window_property_cv(window,GROUP,sizeof(group),group) ||
                m->api.screen_get_window_property_iv(window,TYPE,&kind)) {
                if(window!=m->child && window!=m->parent) m->api.screen_destroy_window(window);
                goto fail;
            }
            id[63]=group[63]=0;
            if (strcmp(id,m->video) || strcmp(group,m->group) || kind!=2) {
                if(window!=m->child && window!=m->parent && m->api.screen_destroy_window(window)) goto fail;
                continue;
            }
            if (m->child && m->child!=window) { m->api.screen_destroy_window(window); goto fail; }
            m->child=window;
            if (geometry(m)) goto fail;
        } else if (window==m->child) {
            if (type==3) goto fail;
            if (m->posts!=UINT32_MAX) ++m->posts;
        } else if(window!=m->parent && m->api.screen_destroy_window(window)) {
            goto fail;
        }
    }
    if(m->child && readback(m)) goto fail;
    return 0;
fail:
    m->failed=1; obd_media_close(m); return -1;
}
int obd_media_submit(obd_media *m, const obd_lhf2_frame *frame, const uint8_t *data, size_t bytes) {
    if (!m || !m->ready || m->failed || !frame || !data ||
        obd_lhf2_validate(&m->sequence,frame,data,bytes)) return -1;
    if (send_message(m,1,data,(unsigned)bytes)) { m->failed=1; obd_media_close(m); return -1; }
    obd_lhf2_commit(&m->sequence,frame);
    if (m->submitted!=UINT32_MAX) ++m->submitted;
    return 0;
}
