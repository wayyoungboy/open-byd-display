#include "process_wait.h"
#ifdef OBD_IO_HOST
#include <sys/wait.h>
#else
extern int waitpid(int,int *,int);
/* Fixed 6.6 runtime: original nonblocking wait call sites pass 0x40;
 * libc waitpid forwards options to wait4. This is NOT the Linux/macOS flag. */
enum { WNOHANG=0x40 };
#endif
int obd_child_poll(int pid,int *status) { return pid<=1 || !status?-1:waitpid(pid,status,WNOHANG); }
