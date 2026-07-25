package qupath.ext.histopia;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class HistopiaProcess {

    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(2);
    private static final ScheduledExecutorService TERMINATOR =
            Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "histopia-process-terminator");
                thread.setDaemon(true);
                return thread;
            });

    private HistopiaProcess() {
    }

    static void cancelTree(Process process) {
        cancelTree(process, DEFAULT_GRACE);
    }

    static void cancelTree(Process process, Duration grace) {
        if (process == null)
            return;
        if (grace == null || grace.isNegative())
            throw new IllegalArgumentException("Process termination grace must be non-negative");
        var handles = processTree(process);
        signal(handles, false);
        var escalation = TERMINATOR.schedule(
                () -> signal(expandLivingTrees(handles), true),
                grace.toMillis(),
                TimeUnit.MILLISECONDS);
        CompletableFuture.allOf(handles.stream()
                        .map(TrackedProcess::exit)
                        .toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> escalation.cancel(false));
    }

    private static List<TrackedProcess> processTree(Process process) {
        var handles = new ArrayList<>(process.descendants().toList());
        Collections.reverse(handles);
        handles.add(process.toHandle());
        return handles.stream().map(TrackedProcess::new).toList();
    }

    private static List<TrackedProcess> expandLivingTrees(
            List<TrackedProcess> tracked) {
        var byPid = new LinkedHashMap<Long, TrackedProcess>();
        for (var process : tracked) {
            if (process.exit().isDone())
                continue;
            byPid.putIfAbsent(process.handle().pid(), process);
            process.handle().descendants()
                    .map(TrackedProcess::new)
                    .forEach(descendant ->
                            byPid.putIfAbsent(descendant.handle().pid(), descendant));
        }
        var expanded = new ArrayList<>(byPid.values());
        expanded.sort((left, right) ->
                Integer.compare(depth(right.handle()), depth(left.handle())));
        return expanded;
    }

    private static int depth(ProcessHandle process) {
        var depth = 0;
        var current = process;
        while (depth < 256) {
            var parent = current.parent();
            if (parent.isEmpty())
                break;
            current = parent.get();
            depth++;
        }
        return depth;
    }

    private static void signal(List<TrackedProcess> processes, boolean force) {
        for (var process : processes) {
            if (process.exit().isDone())
                continue;
            try {
                if (force)
                    process.handle().destroyForcibly();
                else
                    process.handle().destroy();
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // Best effort; the run loop remains responsible for observing exit.
            }
        }
    }

    private record TrackedProcess(
            ProcessHandle handle,
            CompletableFuture<ProcessHandle> exit) {

        private TrackedProcess(ProcessHandle handle) {
            this(handle, handle.onExit());
        }
    }
}
