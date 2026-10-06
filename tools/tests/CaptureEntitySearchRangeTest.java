package com.xfw.shuttershadow.util;

import java.util.concurrent.CompletableFuture;

/** 直接测试生产覆盖范围，尤其是异常、嵌套和跨线程清理。 */
public final class CaptureEntitySearchRangeTest {
    private static int passed;

    public static void main(String[] args) {
        run("ordinary queries keep the original range", () -> {
            check(CaptureEntitySearchRange.currentOr(128) == 128, "default range changed");
            check(CaptureEntitySearchRange.currentOr(96) == 96, "original operation argument was ignored");
        });
        run("configured ranges and query result are forwarded", () -> {
            for (int radius : new int[]{1, 16, 128, 256, 512}) {
                Object expected = new Object();
                Object result = CaptureEntitySearchRange.withRadius(radius, () -> {
                    check(CaptureEntitySearchRange.currentOr(128) == radius, "configured range was ignored");
                    return expected;
                });
                check(result == expected, "query result was replaced");
                check(CaptureEntitySearchRange.currentOr(128) == 128, "range leaked into the next query");
            }
        });
        run("nested creature queries restore the outer range", () -> {
            CaptureEntitySearchRange.withRadius(16, () -> {
                CaptureEntitySearchRange.withRadius(256, () -> {
                    check(CaptureEntitySearchRange.currentOr(128) == 256, "inner range was ignored");
                    return null;
                });
                check(CaptureEntitySearchRange.currentOr(128) == 16, "outer range was lost");
                return null;
            });
        });
        run("nested ordinary queries do not inherit creature range", () -> {
            CaptureEntitySearchRange.withRadius(16, () -> {
                CaptureEntitySearchRange.withRadius(null, () -> {
                    check(CaptureEntitySearchRange.currentOr(96) == 96, "ordinary query inherited creature range");
                    return null;
                });
                check(CaptureEntitySearchRange.currentOr(128) == 16, "ordinary query cleared outer range");
                return null;
            });
        });
        run("failed queries propagate the same exception and clear range", () -> {
            RuntimeException expected = new RuntimeException("query failure");
            try {
                CaptureEntitySearchRange.withRadius(512, () -> { throw expected; });
                throw new AssertionError("query exception was swallowed");
            } catch (RuntimeException actual) {
                check(actual == expected, "query exception was replaced");
            }
            check(CaptureEntitySearchRange.currentOr(128) == 128, "failed query leaked range");
        });
        run("nested failure restores the outer range", () -> {
            CaptureEntitySearchRange.withRadius(16, () -> {
                try {
                    CaptureEntitySearchRange.withRadius(256, () -> { throw new IllegalStateException(); });
                    throw new AssertionError("nested exception was swallowed");
                } catch (IllegalStateException expected) {
                    check(CaptureEntitySearchRange.currentOr(128) == 16, "nested failure lost outer range");
                }
                return null;
            });
        });
        run("other threads keep an independent range", () -> {
            CaptureEntitySearchRange.withRadius(16, () -> {
                CompletableFuture.runAsync(() -> {
                    check(CaptureEntitySearchRange.currentOr(128) == 128, "another thread inherited the range");
                    CaptureEntitySearchRange.withRadius(256, () -> {
                        check(CaptureEntitySearchRange.currentOr(128) == 256, "thread-local range failed");
                        return null;
                    });
                    check(CaptureEntitySearchRange.currentOr(128) == 128, "worker thread leaked its range");
                }).join();
                check(CaptureEntitySearchRange.currentOr(128) == 16, "worker thread changed outer range");
                return null;
            });
        });
        check(CaptureEntitySearchRange.currentOr(128) == 128, "tests left an active override");
        System.out.println("Capture entity search range: " + passed + " passed, 0 failed.");
    }

    private static void run(String name, Runnable test) {
        test.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
