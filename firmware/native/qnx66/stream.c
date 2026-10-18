#include "stream.h"
#include "io.h"
#include <string.h>
extern void *malloc(size_t);
extern void free(void *);
extern int snprintf(char *,size_t,const char *,...);
typedef struct { obd_io io; int input,output,continuous; const obd_media_config *bounds; } session;
static int transfer(session *s,void *data,unsigned bytes,int writing) {
    if(s->continuous) {
        uint64_t now=s->io.api.now();
        if(!now) return -1;
        s->io.deadline=now+5000;
    }
    return obd_io_exact(&s->io,writing?s->output:s->input,data,bytes,writing);
}
static uint32_t be32(const uint8_t *p) {
    return (uint32_t)p[0]<<24 | (uint32_t)p[1]<<16 | (uint32_t)p[2]<<8 | p[3];
}
static int message(session *s,const char *stage,unsigned submitted,unsigned posts,
                   int readback,int cleanup,uint32_t sequence) {
    char line[1024],extra[420]={0};
    if(!strcmp(stage,"decoder_submitted"))
        snprintf(extra,sizeof(extra),",\"ack\":%u,\"accepted_sequence\":%u",sequence,sequence);
    if(!strcmp(stage,"heartbeat"))
        snprintf(extra,sizeof(extra),",\"heartbeat\":%u",sequence);
    if(!strcmp(stage,"configured"))
        snprintf(extra,sizeof(extra),",\"configured\":%u",sequence);
    if(!strcmp(stage,"ready") || !strcmp(stage,"capabilities")) {
        const obd_media_config *c=s->bounds;
        snprintf(extra,sizeof(extra),",\"window_protocol\":1,\"hmi_control\":false,\"native_overlay\":false,\"production_flashable\":false,\"bounds\":[%d,%d,%d,%d],\"display_id\":%d,\"physical_width\":%d,\"physical_height\":%d",c->x,c->y,c->width,c->height,c->display_id,c->physical_width,c->physical_height);
    }
    int n=snprintf(line,sizeof(line),"{\"ok\":true,\"service\":\"openbyddisplay.video\",\"wire_version\":1,\"stage\":\"%s\",\"ready\":%s,\"protocol\":\"LHF2\",\"heartbeat_protocol\":1,\"continuous\":%s,\"done\":%s,\"cleanup\":%s,\"submitted\":%u,\"screen_posts\":%u,\"property_readback\":%s,\"visual_verified\":false%s}\n",stage,!strcmp(stage,"ready")?"true":"false",s->continuous?"true":"false",!strcmp(stage,"done")?"true":"false",cleanup?"true":"false",submitted,posts,readback?"true":"false",extra);
    return n<0 || (size_t)n>=sizeof(line) ? -1 : transfer(s,line,(unsigned)n,1);
}
int obd_stream_run(obd_media *m,const obd_media_api *api,const obd_media_config *base,
                   int input,int output,int continuous,const volatile int *cancel) {
    if(!m || !api || !base || input<0 || output<0 || (continuous!=0 && continuous!=1)) return 64;
    session s; memset(&s,0,sizeof(s));
    obd_io_platform(&s.io.api); s.input=input; s.output=output; s.continuous=continuous; s.io.cancel=cancel; s.bounds=base;
    uint64_t now=s.io.api.now();
    if(!now) return 71;
    s.io.deadline=now+30000;
    const char *error="handshake";
    uint8_t greeting[39];
    if(transfer(&s,greeting,4,0)) return 65;
    if(!memcmp(greeting,"INFO",4)) return message(&s,"capabilities",0,0,0,0,0)?74:0;
    if(transfer(&s,greeting+4,sizeof(greeting)-4,0) || memcmp(greeting,"BVL1\0\0\0\17{\"op\":\"stream\"}",23) || be32(greeting+35)) return 65;
    obd_media_config config=*base;
    config.encoded_width=be32(greeting+23); config.encoded_height=be32(greeting+27); config.fps=be32(greeting+31);
    if(obd_media_open(m,api,&config)) return m->failed==2 ? 75 : 70;
    uint8_t *payload=malloc(OBD_LHF2_MAX_PAYLOAD);
    int result=74,ended=0;
    error="ready";
    if(!payload || message(&s,"ready",0,0,0,0,0)) goto done;
    for(;;) {
        uint8_t header[24]; obd_lhf2_frame frame;
        error="window_event";
        if(obd_media_events(m)) goto done;
        error="header";
        if(transfer(&s,header,4,0)) goto done;
        if(!memcmp(header,"PING",4)) {
            error="heartbeat";
            if(transfer(&s,header+4,4,0) || message(&s,"heartbeat",m->submitted,m->posts,m->readback,0,be32(header+4))) goto done;
            continue;
        }
        if(!memcmp(header,"CONF",4)) {
            uint8_t data[24]; error="window_configuration";
            if(transfer(&s,data,sizeof(data),0)) goto done;
            uint32_t x=be32(data+4),y=be32(data+8),w=be32(data+12),h=be32(data+16),alpha=be32(data+20);
            /* Client can only shrink/move inside the operator-calibrated rectangle. */
            if(x<(unsigned)base->x || y<(unsigned)base->y || w<2 || h<2 ||
                w>(unsigned)base->width || h>(unsigned)base->height || alpha>255 ||
                x>(unsigned)(base->x+base->width)-w || y>(unsigned)(base->y+base->height)-h ||
                obd_media_window(m,(int)x,(int)y,(int)w,(int)h,(int)alpha) ||
                message(&s,"configured",m->submitted,m->posts,m->readback,0,be32(data))) goto done;
            continue;
        }
        if(memcmp(header,"LHF2",4) || transfer(&s,header+4,20,0) || obd_lhf2_header(header,sizeof(header),&frame)) goto done;
        if(!frame.bytes) { ended=1; break; }
        error="frame_limit";
        if(m->submitted==UINT32_MAX || (!continuous && m->submitted>=60)) goto done;
        error="payload";
        if(transfer(&s,payload,frame.bytes,0)) goto done;
        error="frame_validation";
        if(obd_lhf2_validate(&m->sequence,&frame,payload,frame.bytes)) goto done;
        error="decoder_submit";
        if(obd_media_submit(m,&frame,payload,frame.bytes)) goto done;
        error="window_event";
        if(obd_media_events(m)) goto done;
        error="frame_receipt";
        if(message(&s,"decoder_submitted",m->submitted,m->posts,m->readback,0,m->sequence.sequence)) goto done;
    }
    error="empty_stream";
    if(!ended || !m->submitted) goto done;
    error="window_completion";
    for(unsigned i=0;i<20;i++) {
        if((cancel && *cancel) || obd_media_events(m)) goto done;
        if(m->child && m->readback && m->posts) break;
        if(s.io.api.poll(0,0,100)<0) goto done;
    }
    if(!m->child || !m->readback || !m->posts) goto done;
    result=0;
done:;
    unsigned submitted=m->submitted,posts=m->posts;
    int readback=m->readback;
    if(obd_media_close(m)) { result=75; error="cleanup"; }
    if(!result && message(&s,"done",submitted,posts,readback,1,0)) { result=74; error="completion_receipt"; }
    if(result && !(cancel && *cancel)) {
        char line[256];
        int n=snprintf(line,sizeof(line),"{\"ok\":false,\"error\":\"%s\",\"submitted\":%u,\"visual_verified\":false}\n",error,submitted);
        if(n>0 && (size_t)n<sizeof(line)) (void)transfer(&s,line,(unsigned)n,1);
    }
    free(payload); return result;
}
