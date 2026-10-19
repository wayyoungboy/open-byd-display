#include "baseline_platform.h"
#include "baseline.h"
#include "readonly.h"
#include "obd_baseline_generated.h"
#include <stddef.h>
extern int write(int,const void *,unsigned);
extern int snprintf(char *,size_t,const char *,...);
static int report(int fd,const char *event,unsigned index) {
    char line[512];
    int n=snprintf(line,sizeof(line),"{\"event\":\"%s\",\"target\":\"%s\",\"base_version\":\"%s\",\"baseline_zip_sha256\":\"%s\",\"file_index\":%u,\"service_port\":47792,\"hardware_verified\":false,\"visual_verified\":false,\"production_flashable\":false}\n",event,OBD_TARGET_ID,OBD_BASE_VERSION,OBD_BASE_ZIP_SHA256,index);
    return n<=0 || (size_t)n>=sizeof(line) || write(fd,line,(unsigned)n)!=n ? -1 : 0;
}
int obd_baseline_identity(void) { return report(1,"compiled_baseline",0)?74:0; }
const char *obd_baseline_target(void) { return OBD_TARGET_ID; }
const char *obd_baseline_zip(void) { return OBD_BASE_ZIP_SHA256; }
int obd_baseline_platform_check(const volatile int *cancel) {
    obd_readonly_file file={-1,0,{0}};
    const obd_baseline_io io={&file,obd_readonly_open,obd_readonly_read,obd_readonly_close}; unsigned index=0;
    if(obd_baseline_check(obd_compiled_baseline_files,4,&io,cancel,&index)) {
        (void)report(2,"baseline_mismatch",index); return 78;
    }
    /* Never write to stdout here: it is the video socket after admission. */
    return report(2,"baseline_fingerprints_matched",index)?74:0;
}
