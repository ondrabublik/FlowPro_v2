package flowpro.core.parallel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;

/**
 * Runs work on a shared pool of daemon threads. Previously a new set of
 * threads was created for every matrix-vector product, every application of
 * the preconditioner, every assembly etc., i.e. thousands of threads per time
 * step.
 *
 * @author obublik
 */
public final class ParallelLoop {

    private static ExecutorService pool;
    private static int poolSize;

    private ParallelLoop() {
        throw new UnsupportedOperationException();
    }

    private static synchronized ExecutorService getPool(int nThreads) {
        if (pool == null || poolSize < nThreads) {
            if (pool != null) {
                pool.shutdown();
            }
            poolSize = nThreads;
            pool = Executors.newFixedThreadPool(nThreads, r -> {
                Thread thread = new Thread(r, "flowpro-worker");
                thread.setDaemon(true);
                return thread;
            });
        }
        return pool;
    }

    /**
     * Calls task.accept(id) for id = 0, ..., nThreads - 1 in parallel and waits
     * until all the calls finish.
     *
     * @param nThreads number of parallel tasks
     * @param task the task, its argument is the id of the task
     */
    public static void run(int nThreads, IntConsumer task) {
        if (nThreads <= 1) {
            task.accept(0);
            return;
        }
        List<Callable<Void>> tasks = new ArrayList<>(nThreads);
        for (int v = 0; v < nThreads; v++) {
            final int id = v;
            tasks.add(() -> {
                task.accept(id);
                return null;
            });
        }
        try {
            for (Future<Void> future : getPool(nThreads).invokeAll(tasks)) {
                future.get();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("parallel computation was interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * Calls body.accept(i) for i = 0, ..., n - 1, the indexes are distributed
     * among nThreads tasks cyclically (task v processes v, v + nThreads, ...).
     */
    public static void forEach(int n, int nThreads, IntConsumer body) {
        run(nThreads, id -> {
            for (int i = id; i < n; i += nThreads) {
                body.accept(i);
            }
        });
    }
}
