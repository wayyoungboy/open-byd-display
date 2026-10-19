#include "process_wait.h"
#include <stdio.h>
#include <stdlib.h>
static unsigned calls;
int obd_wait_fixture(int pid,int *status,int options) {
    if(pid!=123 || !status || options!=0x40) exit(1);
    calls++; *status=0; return calls==1?0:pid;
}
int main(void) {
    int status;
    if(obd_child_poll(0,&status)!=-1 || obd_child_poll(-1,&status)!=-1 || obd_child_poll(1,&status)!=-1 ||
       obd_child_poll(123,0)!=-1 || calls || obd_child_poll(123,&status)!=0 || obd_child_poll(123,&status)!=123 || calls!=2) return 1;
    puts("non-host QNX waitpid ABI: positive owned child, WNOHANG=0x40"); return 0;
}
