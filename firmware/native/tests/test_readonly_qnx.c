#include "readonly.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"stat ABI failed %d: %s\n",__LINE__,#x); exit(1); } } while(0)
static unsigned lstats,fstats,opens,closes,reads;
static unsigned mode=0100500;
static int fail_open,fail_stat,wrong_inode,change_path;
static void word(uint8_t *p,unsigned n,uint32_t v) { for(unsigned i=0;i<4;i++) p[n+i]=(uint8_t)(v>>(8*i)); }
static int stat_data(void *out,int descriptor) {
    if(fail_stat) return -1;
    uint8_t *p=out; memset(p,0,72);
    word(p,0,descriptor && wrong_inode?8:7); word(p,8,3); word(p,16,5);
    word(p,24,0); word(p,32,11); word(p,40,13); word(p,44,mode); word(p,48,1); return 0;
}
int obd_fixture_lstat(const char *path,void *out) {
    CHECK(!strcmp(path,"/fixture")); lstats++;
    if(change_path && lstats>1) { unsigned saved=mode; mode=0120777; int r=stat_data(out,0); mode=saved; return r; }
    return stat_data(out,0);
}
int obd_fixture_fstat(int fd,void *out) { CHECK(fd==19); fstats++; return stat_data(out,1); }
int obd_fixture_open(const char *path,int flags,...) { CHECK(!strcmp(path,"/fixture") && flags==0x80); opens++; return fail_open?-1:19; }
int obd_fixture_read(int fd,void *out,unsigned n) { CHECK(fd==19 && n<=3); reads++; memcpy(out,"abc",n); return (int)n; }
int obd_fixture_close(int fd) { CHECK(fd==19); closes++; return 0; }
int main(void) {
    obd_readonly_file file={-1,0,{0}}; char data[4];
    int fd=obd_readonly_open(&file,"/fixture",3); CHECK(fd==19);
    CHECK(obd_readonly_read(&file,fd,data,3)==3); CHECK(!obd_readonly_close(&file,fd));
    CHECK(lstats==3 && fstats==2 && opens==1 && closes==1 && reads==1);
    for(unsigned i=0;i<5;i++) {
        unsigned bad[]={0010600,0020600,0040700,0060600,0120777}; mode=bad[i];
        CHECK(obd_readonly_open(&file,"/fixture",3)==-1); CHECK(opens==1);
    }
    mode=0100500; wrong_inode=1;
    CHECK(obd_readonly_open(&file,"/fixture",3)==-1); CHECK(closes==2);
    wrong_inode=0; change_path=1; lstats=0;
    CHECK(obd_readonly_open(&file,"/fixture",3)==-1); CHECK(closes==3);
    change_path=0; fail_open=1;
    CHECK(obd_readonly_open(&file,"/fixture",3)==-1); CHECK(closes==3);
    fail_open=0; fail_stat=1;
    CHECK(obd_readonly_open(&file,"/fixture",3)==-1); CHECK(closes==3);
    puts("QNX72-byte stat layout and NONBLOCK flags tested against explicit syscall fixtures"); return 0;
}
