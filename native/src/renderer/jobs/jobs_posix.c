#include "../common/renderer_internal.h"

#include <pthread.h>
#include <stdlib.h>
#include <unistd.h>

struct nar_job_system {
    pthread_t *threads;
    uint32_t thread_count;   /* background threads */
    uint32_t worker_count;   /* background + calling thread */
    pthread_mutex_t mutex;
    pthread_cond_t start_cond;
    pthread_cond_t done_cond;
    uint64_t generation;
    uint32_t next_index;
    uint32_t count;
    uint32_t active_background;
    nar_parallel_fn function;
    void *userdata;
    int shutdown;
};

typedef struct nar_worker_arg {
    struct nar_job_system *jobs;
} nar_worker_arg;

static int take_job(struct nar_job_system *jobs, uint32_t *out_index, nar_parallel_fn *out_fn, void **out_userdata) {
    int have = 0;
    pthread_mutex_lock(&jobs->mutex);
    if (jobs->next_index < jobs->count) {
        *out_index = jobs->next_index++;
        *out_fn = jobs->function;
        *out_userdata = jobs->userdata;
        have = 1;
    }
    pthread_mutex_unlock(&jobs->mutex);
    return have;
}

static void *worker_main(void *arg) {
    nar_worker_arg *worker_arg = (nar_worker_arg *)arg;
    struct nar_job_system *jobs = worker_arg->jobs;
    free(worker_arg);
    uint64_t observed_generation = 0;

    for (;;) {
        pthread_mutex_lock(&jobs->mutex);
        while (!jobs->shutdown && observed_generation == jobs->generation) {
            pthread_cond_wait(&jobs->start_cond, &jobs->mutex);
        }
        if (jobs->shutdown) {
            pthread_mutex_unlock(&jobs->mutex);
            return NULL;
        }
        observed_generation = jobs->generation;
        pthread_mutex_unlock(&jobs->mutex);

        for (;;) {
            uint32_t index;
            nar_parallel_fn function;
            void *userdata;
            if (!take_job(jobs, &index, &function, &userdata)) break;
            function(userdata, index);
        }

        pthread_mutex_lock(&jobs->mutex);
        if (jobs->active_background > 0 && --jobs->active_background == 0) {
            pthread_cond_signal(&jobs->done_cond);
        }
        pthread_mutex_unlock(&jobs->mutex);
    }
}

static uint32_t choose_worker_count(uint32_t requested) {
    if (requested != 0) return requested;
    long cpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpus < 1) cpus = 1;
    if (cpus > 64) cpus = 64;
    return (uint32_t)cpus;
}

struct nar_job_system *nar_jobs_create(uint32_t requested_worker_count) {
    struct nar_job_system *jobs = (struct nar_job_system *)calloc(1, sizeof(*jobs));
    if (jobs == NULL) return NULL;
    jobs->worker_count = choose_worker_count(requested_worker_count);
    jobs->thread_count = jobs->worker_count > 1 ? jobs->worker_count - 1u : 0u;
    if (pthread_mutex_init(&jobs->mutex, NULL) != 0 ||
        pthread_cond_init(&jobs->start_cond, NULL) != 0 ||
        pthread_cond_init(&jobs->done_cond, NULL) != 0) {
        free(jobs);
        return NULL;
    }
    if (jobs->thread_count == 0) return jobs;

    jobs->threads = (pthread_t *)calloc(jobs->thread_count, sizeof(*jobs->threads));
    if (jobs->threads == NULL) {
        nar_jobs_destroy(jobs);
        return NULL;
    }
    for (uint32_t i = 0; i < jobs->thread_count; ++i) {
        nar_worker_arg *arg = (nar_worker_arg *)malloc(sizeof(*arg));
        if (arg == NULL) {
            jobs->thread_count = i;
            nar_jobs_destroy(jobs);
            return NULL;
        }
        arg->jobs = jobs;
        if (pthread_create(&jobs->threads[i], NULL, worker_main, arg) != 0) {
            free(arg);
            jobs->thread_count = i;
            nar_jobs_destroy(jobs);
            return NULL;
        }
    }
    return jobs;
}

void nar_jobs_destroy(struct nar_job_system *jobs) {
    if (jobs == NULL) return;
    pthread_mutex_lock(&jobs->mutex);
    jobs->shutdown = 1;
    jobs->generation++;
    pthread_cond_broadcast(&jobs->start_cond);
    pthread_mutex_unlock(&jobs->mutex);
    for (uint32_t i = 0; i < jobs->thread_count; ++i) pthread_join(jobs->threads[i], NULL);
    free(jobs->threads);
    pthread_cond_destroy(&jobs->done_cond);
    pthread_cond_destroy(&jobs->start_cond);
    pthread_mutex_destroy(&jobs->mutex);
    free(jobs);
}

uint32_t nar_jobs_worker_count(const struct nar_job_system *jobs) {
    return jobs == NULL ? 0u : jobs->worker_count;
}

int32_t nar_jobs_parallel_for(struct nar_job_system *jobs, uint32_t count, nar_parallel_fn function, void *userdata) {
    if (jobs == NULL || function == NULL) return -1;
    if (count == 0) return 0;
    if (jobs->thread_count == 0 || count == 1) {
        for (uint32_t i = 0; i < count; ++i) function(userdata, i);
        return 0;
    }

    pthread_mutex_lock(&jobs->mutex);
    jobs->function = function;
    jobs->userdata = userdata;
    jobs->count = count;
    jobs->next_index = 0;
    jobs->active_background = jobs->thread_count;
    jobs->generation++;
    pthread_cond_broadcast(&jobs->start_cond);
    pthread_mutex_unlock(&jobs->mutex);

    for (;;) {
        uint32_t index;
        nar_parallel_fn fn;
        void *job_userdata;
        if (!take_job(jobs, &index, &fn, &job_userdata)) break;
        fn(job_userdata, index);
    }

    pthread_mutex_lock(&jobs->mutex);
    while (jobs->active_background != 0) pthread_cond_wait(&jobs->done_cond, &jobs->mutex);
    pthread_mutex_unlock(&jobs->mutex);
    return 0;
}
