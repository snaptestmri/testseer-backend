package io.testseer.backend.ingestion.maven;

import io.testseer.backend.config.MavenProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MavenTreeResolutionExecutor {

    private final ExecutorService executor;

    public MavenTreeResolutionExecutor(MavenProperties mavenProperties) {
        int parallelism = Math.max(1, mavenProperties.getTreeParallelism());
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "maven-tree-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(parallelism, factory);
    }

    public <T> List<T> invokeAll(List<Callable<T>> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        try {
            List<Future<T>> futures = executor.invokeAll(tasks);
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Maven tree resolution interrupted", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Maven tree resolution failed", ex.getCause());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
