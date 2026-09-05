#ifndef NATIVE_ACCELERATOR_RENDERER_INTERNAL_H
#define NATIVE_ACCELERATOR_RENDERER_INTERNAL_H

#include "native_accelerator_renderer.h"

struct nar_job_system;

struct nar_context {
    uint32_t worker_count;
    struct nar_job_system *jobs;
};

struct nar_job_system *nar_jobs_create(uint32_t worker_count);
void nar_jobs_destroy(struct nar_job_system *jobs);
uint32_t nar_jobs_worker_count(const struct nar_job_system *jobs);
int32_t nar_jobs_parallel_for(struct nar_job_system *jobs, uint32_t count, nar_parallel_fn function, void *userdata);

#endif
