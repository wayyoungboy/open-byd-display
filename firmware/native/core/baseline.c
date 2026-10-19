#include "baseline.h"
#include "sha256.h"
static int hex(char c) { return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:-1; }
int obd_baseline_check(const obd_baseline_file *files,unsigned count,const obd_baseline_io *io,
                       const volatile int *cancel,unsigned *failed_index) {
    if(failed_index) *failed_index=0;
    if(!files || !count || count>8 || !io || !io->open || !io->read || !io->close || !failed_index) return -1;
    for(unsigned i=0;i<count;i++) {
        *failed_index=i;
        const obd_baseline_file *f=files+i;
        if(!f->path || f->path[0]!='/' || !f->bytes || f->bytes>32u*1024*1024 || !f->sha256) return -1;
        uint8_t expected[32],actual[32],buffer[4096];
        for(unsigned j=0;j<64;j++) {
            if(!f->sha256[j] || hex(f->sha256[j])<0) return -1;
            if(j%2==0) expected[j/2]=(uint8_t)(hex(f->sha256[j])<<4);
            else expected[j/2]|=(uint8_t)hex(f->sha256[j]);
        }
        if(f->sha256[64] || (cancel && *cancel)) return -1;
        int fd=io->open(io->context,f->path,f->bytes);
        if(fd<0) return -1;
        obd_sha256 hash; obd_sha256_init(&hash);
        uint32_t used=0; int result=-1;
        for(;;) {
            if(cancel && *cancel) break;
            unsigned wanted=f->bytes-used;
            if(wanted>sizeof(buffer)) wanted=sizeof(buffer);
            if(!wanted) wanted=1; /* Require EOF, not only a matching prefix. */
            int n=io->read(io->context,fd,buffer,wanted);
            if(n<0 || (unsigned)n>wanted || (unsigned)n>f->bytes-used) break;
            if(!n) {
                if(used!=f->bytes) break;
                obd_sha256_final(&hash,actual); result=0;
                for(unsigned j=0;j<32;j++) if(actual[j]!=expected[j]) result=-1;
                break;
            }
            used+=(unsigned)n; obd_sha256_update(&hash,buffer,(unsigned)n);
        }
        if(io->close(io->context,fd)) result=-1;
        if(result) return -1;
    }
    *failed_index=count; return 0;
}
