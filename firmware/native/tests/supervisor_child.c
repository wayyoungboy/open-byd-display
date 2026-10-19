#define _POSIX_C_SOURCE 200809L
#include "guard.h"
#include "io.h"
#include <signal.h>
#include <stdlib.h>
#include <string.h>
/* Explicit host ABI double, not an implementation of QNX Screen/mm-renderer. */
int main(int argc,char **argv) {
    if(argc!=16 || strcmp(argv[13],"--supervised")) return 64;
    signal(SIGPIPE,SIG_IGN);
    if(obd_guard_start(atoi(argv[14]),atoi(argv[15]))) return 71;
    obd_io_api io; obd_io_platform(&io);
    obd_media media; memset(&media,0,sizeof(media));
    media.child=&media; media.ready=media.readback=media.actual_visible=1;
    for(unsigned i=0;i<1000;i++) {
#ifndef OBD_TEST_CHILD_UNHEALTHY
        media.submitted++; media.posts++; obd_guard_media(&media);
#endif
        if(obd_guard_tick()) { obd_guard_stop(); return 0; }
        io.poll(0,0,100);
    }
    return 72;
}
