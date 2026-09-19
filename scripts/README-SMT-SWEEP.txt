Run the exact same cold-world benchmark command at SMT1/2/4/6/8:

  ./scripts/run-worldgen-smt-sweep.sh ./your-existing-worldgen-benchmark.sh

The invoked command is responsible for the benchmark contract (fresh world, fixed seed, same heap/JVM,
same regions). The wrapper only changes Native Accelerator SMT properties and captures one log per occupancy.
Keep deep profiling off for throughput sweeps. Use a separate deep-profile run after selecting an occupancy.
