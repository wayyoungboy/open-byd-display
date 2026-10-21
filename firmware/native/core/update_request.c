#include "update_request.h"
#include "generation.h"
#include <string.h>
typedef struct { const char *p,*end; } cursor;
static void ws(cursor *c) { while(c->p<c->end && (*c->p==' ' || *c->p=='\t' || *c->p=='\r' || *c->p=='\n')) c->p++; }
static int token(cursor *c,char ch) { ws(c); if(c->p==c->end || *c->p++!=ch) return -1; return 0; }
static int string(cursor *c,char *out,unsigned maximum) {
    if(token(c,'"')) return -1;
    unsigned n=0;
    while(c->p<c->end && *c->p!='"') {
        unsigned ch=(unsigned char)*c->p++;
        if(ch<32 || ch>126 || ch=='\\' || n+1>=maximum) return -1;
        out[n++]=(char)ch;
    }
    out[n]=0;
    return c->p==c->end?-1:(c->p++,0);
}
static int hex(const char *s,unsigned bytes) {
    uint8_t raw[32]; return bytes>32 || strlen(s)!=bytes*2 || obd_generation_unhex(raw,s,bytes);
}
int obd_update_parse(obd_update_request *out,const char *text,size_t bytes) {
    if(!out || !text || !bytes || bytes>OBD_UPDATE_LINE || memchr(text,0,bytes)) return -1;
    memset(out,0,sizeof(*out)); cursor c={text,text+bytes}; unsigned seen=0;
    if(token(&c,'{')) return -1;
    for(unsigned count=0;count<9;count++) {
        char key[32],value[80]; unsigned bit=0;
        if(string(&c,key,sizeof(key)) || token(&c,':')) return -1;
        static const char *keys[]={"op","update_id","kind","bytes","sha256","target","baseline_sha256","parked","confirm"};
        for(unsigned i=0;i<9;i++) if(!strcmp(key,keys[i])) bit=1u<<i;
        if(!bit || (seen&bit)) return -1;
        seen|=bit;
        if(bit==(1u<<3)) {
            ws(&c); unsigned n=0,digits=0; const char *start=c.p;
            while(c.p<c.end && *c.p>='0' && *c.p<='9') {
                if(++digits>9) return -1;
                n=n*10+(unsigned)(*c.p++-'0');
            }
            if(!digits || (digits>1 && *start=='0') || !n || n>OBD_GEN_HEADER+OBD_GEN_FILES*OBD_GEN_ROW+OBD_GEN_PAYLOAD) return -1;
            out->bytes=n;
        } else if(bit==(1u<<7)) {
            ws(&c); if(c.end-c.p<4 || memcmp(c.p,"true",4)) return -1;
            c.p+=4; out->parked=1;
        } else {
            if(string(&c,value,sizeof(value))) return -1;
            if(bit==1) {
                static const char *ops[]={"update.info","update.upload","update.status","update.apply","update.cancel"};
                for(unsigned i=0;i<5;i++) if(!strcmp(value,ops[i])) out->op=i+1;
                if(!out->op) return -1;
            } else if(bit==(1u<<1)) {
                if(hex(value,16)) return -1;
                unsigned nonzero=0; for(unsigned i=0;i<32;i++) nonzero|=value[i]!='0';
                if(!nonzero) return -1;
                memcpy(out->id,value,33);
            } else if(bit==(1u<<2)) {
                if(strcmp(value,"native_generation")) return -1;
            } else if(bit==(1u<<4) || bit==(1u<<6)) {
                if(hex(value,32)) return -1;
                memcpy(bit==(1u<<4)?out->sha256:out->baseline,value,65);
            } else if(bit==(1u<<5)) {
                if(strcmp(value,"song-plus-dmi-2021") && strcmp(value,"tang-plus-dmi-2021")) return -1;
                memcpy(out->target,value,strlen(value)+1);
            } else if(bit==(1u<<8) && strcmp(value,"INSTALL_NATIVE_GENERATION")) return -1;
        }
        ws(&c); if(c.p==c.end) return -1;
        if(*c.p=='}') { c.p++; break; }
        if(*c.p++!=',') return -1;
    }
    ws(&c); if(c.p!=c.end) return -1;
    unsigned required=out->op==OBD_UPDATE_INFO?1:
        out->op==OBD_UPDATE_UPLOAD?127:out->op==OBD_UPDATE_APPLY?387:3;
    return seen==required?0:-1;
}
