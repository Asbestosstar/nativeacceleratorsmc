#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nativeaccelerator-dax-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/dax.h" <<'HDR'
#ifndef DAX_H
#define DAX_H
#include <stdint.h>
#include <stddef.h>
typedef int dax_status_t;
typedef struct dax_context { int dummy; } dax_context_t;
typedef enum { DAX_GE_AND_LE=1, DAX_LE_OR_GE=2 } dax_compare_t;
typedef struct { uint32_t format; uint32_t elem_width; uint64_t dword[3]; } dax_int_t;
typedef struct dax_vec {
    uint64_t elements; void *data; uint32_t format; uint32_t elem_width; uint32_t offset;
    void *aux_data; uint32_t aux_offset; uint32_t aux_width; void *codec; uint64_t codewords;
} dax_vec_t;
typedef struct { dax_status_t status; uint64_t count; } dax_result_t;
#define DAX_SUCCESS 0
#define DAX_FIXED 0u
#define DAX_BYTES 0u
#define DAX_BITS 1u
#define DAX_CACHE_DST 1u
#define DAX_OUTPUT_SIZE(elements,bits) ((((size_t)(elements)*(size_t)(bits)+511u)/512u)*64u+64u)
dax_status_t dax_thread_init(unsigned,unsigned,uint64_t,void*,dax_context_t**);
dax_status_t dax_thread_fini(dax_context_t*);
dax_status_t dax_int_create(dax_context_t*,void*,size_t,dax_int_t*);
dax_result_t dax_scan_range(dax_context_t*,uint64_t,dax_vec_t*,dax_vec_t*,dax_compare_t,dax_int_t*,dax_int_t*);
dax_result_t dax_select(dax_context_t*,uint64_t,dax_vec_t*,dax_vec_t*,dax_vec_t*);
#endif
HDR

cat > "$TMP/fake_dax.c" <<'SRC'
#include "dax.h"
#include <stdlib.h>
#include <string.h>
static uint32_t be32(const unsigned char *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}
dax_status_t dax_thread_init(unsigned a,unsigned b,uint64_t c,void*d,dax_context_t**out) {
    (void)a;(void)b;(void)c;(void)d; *out=calloc(1,sizeof(**out)); return *out?0:1;
}
dax_status_t dax_thread_fini(dax_context_t*c) { free(c); return 0; }
dax_status_t dax_int_create(dax_context_t*c,void*buf,size_t len,dax_int_t*out) {
    (void)c; if(len!=4)return 1; memset(out,0,sizeof(*out)); out->elem_width=4; out->dword[2]=be32(buf); return 0;
}
dax_result_t dax_scan_range(dax_context_t*c,uint64_t flags,dax_vec_t*src,dax_vec_t*dst,
                            dax_compare_t op,dax_int_t*lo,dax_int_t*hi) {
    (void)c;(void)flags; unsigned char*a=src->data,*m=dst->data; uint64_t n=0;
    memset(m,0,(src->elements+7)/8);
    for(uint64_t i=0;i<src->elements;i++) {
        uint32_t x=be32(a+4*i),l=(uint32_t)lo->dword[2],h=(uint32_t)hi->dword[2];
        int ok=op==DAX_GE_AND_LE?(x>=l&&x<=h):(x<=l||x>=h);
        if(ok){m[i>>3]|=(unsigned char)(0x80u>>(i&7));n++;}
    }
    return (dax_result_t){0,n};
}
dax_result_t dax_select(dax_context_t*c,uint64_t flags,dax_vec_t*src,dax_vec_t*dst,dax_vec_t*mask) {
    (void)c;(void)flags; unsigned char*a=src->data,*o=dst->data,*m=mask->data; uint64_t n=0;
    for(uint64_t i=0;i<src->elements;i++) if(m[i>>3]&(0x80u>>(i&7))) { memcpy(o+4*n,a+4*i,4); n++; }
    return (dax_result_t){0,n};
}
SRC

cat > "$TMP/test.c" <<'SRC'
#include "native_accelerator_dax.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
static void check(int32_t lo,int32_t hi,uint64_t expected) {
    int32_t in[]={-100,-10,-9,-1,0,1,9,10,11,100};
    int32_t out[10]; uint64_t count=0, selected=0;
    int rc=na_dax_i32_count_range(in,10,lo,hi,&count);
    if(rc||count!=expected){fprintf(stderr,"count [%d,%d]: rc=%d got=%llu expected=%llu\n",lo,hi,rc,(unsigned long long)count,(unsigned long long)expected);exit(1);}
    rc=na_dax_i32_select_range(out,10,in,10,lo,hi,&selected);
    if(rc||selected!=expected){fprintf(stderr,"select [%d,%d]: rc=%d got=%llu expected=%llu\n",lo,hi,rc,(unsigned long long)selected,(unsigned long long)expected);exit(1);}
    for(uint64_t i=0;i<selected;i++) if(out[i]<lo||out[i]>hi){fprintf(stderr,"selected value outside signed range: %d\n",out[i]);exit(1);}
}
int main(void) {
    check(0,10,4); check(-10,-1,3); check(-10,10,7);
    check(INT32_MIN,INT32_MAX,10); check(11,99,1);
    puts("DAX adapter checks passed: 5/5");
    return 0;
}
SRC

CC_BIN="${CC:-cc}"
"$CC_BIN" -std=c11 -D_XOPEN_SOURCE=700 -Wall -Wextra -Werror \
    -I"$TMP" -I"$ROOT/native/include" \
    "$ROOT/native/src/os/solaris/dax_int.c" "$TMP/fake_dax.c" "$TMP/test.c" \
    -pthread -o "$TMP/dax-test"
"$TMP/dax-test"
