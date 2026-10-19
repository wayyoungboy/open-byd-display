#ifndef OBD_BASELINE_PLATFORM_H
#define OBD_BASELINE_PLATFORM_H
int obd_baseline_platform_check(const volatile int *cancel);
int obd_baseline_identity(void);
const char *obd_baseline_target(void);
const char *obd_baseline_zip(void);
#endif
