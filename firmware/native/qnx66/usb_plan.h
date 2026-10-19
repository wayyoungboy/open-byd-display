#ifndef OBD_USB_PLAN_H
#define OBD_USB_PLAN_H
#include "usb_txn.h"
typedef struct { char path[96],parameters[256],prefix[128],sock[112]; char *argv[10]; unsigned argc; } obd_usb_command;
/* Fixed argument vectors, not shell text. STOP_* needs a separate proven-owned
 * process adapter and is deliberately not converted into a kill-by-PID string. */
int obd_usb_plan(obd_usb_command *,const char *target,const char *runtime,unsigned action);
#endif
