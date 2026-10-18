#ifndef OBD_QNX66_MINIMAL_STRING_H
#define OBD_QNX66_MINIMAL_STRING_H
#include <stddef.h>
/* ISO C interface used by the portable core; not a copied platform header. */
int memcmp(const void *left, const void *right, size_t bytes);
int strcmp(const char *left, const char *right);
void *memcpy(void *destination, const void *source, size_t bytes);
void *memset(void *destination, int value, size_t bytes);
#endif
