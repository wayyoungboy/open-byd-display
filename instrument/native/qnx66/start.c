#include <stdint.h>

typedef void (*obd_exit_callback)(void);
extern void _init_libc(int argc, char **argv, char **envp, void *auxv,
                       obd_exit_callback callback);
extern void exit(int status) __attribute__((noreturn));
extern int main(int argc, char **argv);

/* Minimal C-only process; no executable constructors, C++, TLS objects or threads. */
void obd_qnx_entry(uint32_t *initial_stack, obd_exit_callback callback)
    __attribute__((noreturn));
void obd_qnx_entry(uint32_t *initial_stack, obd_exit_callback callback) {
    int argc = (int)initial_stack[0];
    char **argv = (char **)(initial_stack + 1);
    char **envp = argv + argc + 1;
    char **end = envp;
    while (*end) ++end;
    _init_libc(argc, argv, envp, end + 1, callback);
    exit(main(argc, argv));
}
