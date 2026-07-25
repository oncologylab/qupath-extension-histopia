package qupath.ext.histopia;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HistopiaProcessTest {

    @Test
    void forciblyTerminatesStubbornProcessTreeAfterGracePeriod() throws Exception {
        assumeTrue(File.separatorChar == '/');
        var process = new ProcessBuilder(
                "sh",
                "-c",
                "trap '' TERM; "
                        + "sh -c \"trap '' TERM; exec sleep 60\" & "
                        + "echo $!; wait")
                .start();
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var child = ProcessHandle.of(Long.parseLong(reader.readLine())).orElseThrow();
        try {
            assertTrue(process.isAlive());
            assertTrue(child.isAlive());

            HistopiaProcess.cancelTree(process, Duration.ofMillis(100));

            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertTrue(waitForExit(child, Duration.ofSeconds(5)));
            assertFalse(process.isAlive());
            assertFalse(child.isAlive());
        } finally {
            child.destroyForcibly();
            process.destroyForcibly();
        }
    }

    private static boolean waitForExit(ProcessHandle process, Duration timeout)
            throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline)
            Thread.sleep(20);
        return !process.isAlive();
    }
}
