#include <string.h>
/* Independent compiler-support adapters; no copied compiler-rt or vendor CRT. */
/* Original libc selects memcpy/memset via IFUNC. Keep this minimal program's
 * byte copies local instead of assuming additional loader relocation support.
 * Volatile stores prevent the compiler from replacing these loops with libcalls.
 */
#define LOCAL __attribute__((visibility("hidden")))
LOCAL void *memcpy(void *d, const void *s, size_t n) {
    volatile unsigned char *out=d; const unsigned char *in=s;
    for(size_t i=0;i<n;i++) out[i]=in[i];
    return d;
}
LOCAL void *memset(void *d, int c, size_t n) {
    volatile unsigned char *out=d;
    for(size_t i=0;i<n;i++) out[i]=(unsigned char)c;
    return d;
}
LOCAL void __aeabi_memcpy(void *d, const void *s, size_t n) { (void)memcpy(d,s,n); }
LOCAL void __aeabi_memcpy4(void *d, const void *s, size_t n) { (void)memcpy(d,s,n); }
LOCAL void __aeabi_memcpy8(void *d, const void *s, size_t n) { (void)memcpy(d,s,n); }
LOCAL void __aeabi_memclr(void *d, size_t n) { (void)memset(d,0,n); }
LOCAL void __aeabi_memclr4(void *d, size_t n) { (void)memset(d,0,n); }
LOCAL void __aeabi_memclr8(void *d, size_t n) { (void)memset(d,0,n); }
