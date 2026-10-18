/* Independent ARM entry adapter for the observed QNX 6.6 process ABI.
 * No kernel startup code or vendor CRT is included. Runtime verification pending.
 */
.syntax unified
.arm
.text
.align 2
.global _start
.type _start, %function
_start:
    mov r1, r0
    mov r0, sp
    bic sp, sp, #7
    b obd_qnx_entry
.size _start, .-_start

/* QNX 6.6 reference note: default total stack, 4 KiB initially allocated. */
.section .note.qnx.stack, "a", %note
.align 2
.long 4, 8, 3
.asciz "QNX"
.long 0, 4096
.section .note.GNU-stack, "", %progbits
