#include "baseline.h"
#include "sha256.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"failed line %d: %s\n",__LINE__,#x); exit(1); } } while(0)
static void digest(const void *p,size_t n,size_t stride,const char *expected) {
    obd_sha256 h; uint8_t d[32]; char text[65]; obd_sha256_init(&h);
    for(size_t at=0;at<n;) { size_t part=n-at<stride?n-at:stride; obd_sha256_update(&h,(const char *)p+at,part); at+=part; }
    obd_sha256_final(&h,d);
    for(unsigned i=0;i<32;i++) (void)snprintf(text+i*2,3,"%02x",d[i]);
    CHECK(strcmp(text,expected)==0);
}
typedef struct { const char *data; unsigned length,pos,opens,closes; int fault,closed,cancel; } fixture;
static int fixture_open(void *v,const char *path,uint32_t size) {
    fixture *f=v; CHECK(strcmp(path,"/fixture")==0 && size==3); f->opens++; f->pos=0;
    return f->fault==1?-1:17;
}
static int fixture_read(void *v,int fd,void *p,unsigned n) {
    fixture *f=v; CHECK(fd==17);
    if(f->fault==2) return -1;
    if(f->fault==3) return (int)n+1;
    if(f->fault==4) f->cancel=1;
    unsigned left=f->length-f->pos; if(n>2) n=2; if(n>left) n=left;
    memcpy(p,f->data+f->pos,n); f->pos+=n; return (int)n;
}
static int fixture_close(void *v,int fd) { fixture *f=v; CHECK(fd==17); f->closes++; return f->closed; }
int main(void) {
    digest("",0,1,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    for(size_t stride=1;stride<70;stride++) {
        digest("abc",3,stride,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        const char *p="abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
        digest(p,strlen(p),stride,"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    }
    char *large=malloc(1000000); CHECK(large); memset(large,'a',1000000);
    digest(large,1000000,4093,"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"); free(large);
    obd_baseline_file row={"/fixture",3,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"};
    fixture f={"abc",3,0,0,0,0,0,0};
    obd_baseline_io io={&f,fixture_open,fixture_read,fixture_close}; unsigned failed=99;
    CHECK(!obd_baseline_check(&row,1,&io,&f.cancel,&failed)); CHECK(failed==1 && f.closes==1);
    for(int fault=1;fault<=4;fault++) {
        f=(fixture){"abc",3,0,0,0,fault,0,0};
        CHECK(obd_baseline_check(&row,1,&io,&f.cancel,&failed));
        CHECK(failed==0 && f.closes==(unsigned)(fault!=1));
    }
    for(unsigned length=2;length<=4;length++) {
        f=(fixture){"abcd",length,0,0,0,0,0,0};
        CHECK((obd_baseline_check(&row,1,&io,&f.cancel,&failed)==0)==(length==3)); CHECK(f.closes==1);
    }
    f=(fixture){"abd",3,0,0,0,0,0,0}; CHECK(obd_baseline_check(&row,1,&io,0,&failed));
    f=(fixture){"abc",3,0,0,0,0,-1,0}; CHECK(obd_baseline_check(&row,1,&io,0,&failed));
    f=(fixture){"abc",3,0,0,0,0,0,1}; CHECK(obd_baseline_check(&row,1,&io,&f.cancel,&failed)); CHECK(f.opens==0);
    row.sha256="broken"; CHECK(obd_baseline_check(&row,1,&io,0,&failed));
    CHECK(obd_baseline_check(&row,0,&io,0,&failed));
    puts("baseline fingerprints and SHA256 checked; no hardware execution"); return 0;
}
