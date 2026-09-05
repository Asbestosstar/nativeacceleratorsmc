#include "../common/renderer_internal.h"

#include <stdlib.h>

struct nar_job_system { uint32_t worker_count; };

struct nar_job_system *nar_jobs_create(uint32_t worker_count) {
    struct nar_job_system *jobs = (struct nar_job_system *)calloc(1, sizeof(*jobs));
    if (jobs != NULL) jobs->worker_count = worker_count == 0 ? 1u : worker_count;
    return jobs;
}
void nar_jobs_destroy(struct nar_job_system *jobs) { free(jobs); }
uint32_t nar_jobs_worker_count(const struct nar_job_system *jobs) { return jobs == NULL ? 0u : jobs->worker_count; }
int32_t nar_jobs_parallel_for(struct nar_job_system *jobs, uint32_t count, nar_parallel_fn function, void *userdata) {
    (void)jobs;
    if (function == NULL) return -1;
    for (uint32_t i = 0; i < count; ++i) function(userdata, i);
    return 0;
}
