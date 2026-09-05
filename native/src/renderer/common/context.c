#include "renderer_internal.h"

#include <stdlib.h>

nar_context *nar_context_create(uint32_t worker_count) {
    nar_context *context = (nar_context *)calloc(1, sizeof(*context));
    if (context == NULL) return NULL;
    context->jobs = nar_jobs_create(worker_count);
    if (context->jobs == NULL) {
        free(context);
        return NULL;
    }
    context->worker_count = nar_jobs_worker_count(context->jobs);
    return context;
}

void nar_context_destroy(nar_context *context) {
    if (context == NULL) return;
    nar_jobs_destroy(context->jobs);
    free(context);
}

uint32_t nar_context_worker_count(const nar_context *context) {
    return context == NULL ? 0u : context->worker_count;
}

int32_t nar_parallel_for(nar_context *context, uint32_t count, nar_parallel_fn function, void *userdata) {
    if (function == NULL) return -1;
    if (count == 0) return 0;
    if (context == NULL || context->jobs == NULL) {
        for (uint32_t i = 0; i < count; ++i) function(userdata, i);
        return 0;
    }
    return nar_jobs_parallel_for(context->jobs, count, function, userdata);
}
