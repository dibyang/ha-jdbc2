package net.sf.hajdbc.invocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 显式启用 HA 子调用的物理收尾保护，不改变未进入作用域的旧调用。
 * 取消只阻止尚未开始的工作；已经进入 JDBC 的工作必须真实退出才能释放调用方。
 * 本类不强制终止驱动。调用方应把回滚和连接归还放在作用域关闭前；
 * 子调用收尾收到的中断延迟至最外层作用域关闭时恢复，避免旧中断取消后续清理。
 * 新到达的中断仍可取消调用，因此这不是不可中断清理或容量许可管理器。
 */
public final class PhysicalInvocationScope implements AutoCloseable {
    private static final ThreadLocal<PhysicalInvocationScope> CURRENT = new ThreadLocal<>();
    private final PhysicalInvocationScope previous;
    private final Thread owner = Thread.currentThread();
    private boolean closed;
    private boolean interrupted;

    private PhysicalInvocationScope() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    /** 在当前线程启用保护，必须使用 try-with-resources 成对关闭。 */
    public static PhysicalInvocationScope open() {
        return new PhysicalInvocationScope();
    }

    /** 返回当前线程是否显式要求物理收尾保护。 */
    static boolean isActive() {
        return CURRENT.get() != null;
    }

    /** 按嵌套顺序恢复线程上下文，不向线程池后续任务泄漏状态。 */
    @Override
    public void close() {
        if (Thread.currentThread() != owner || closed || CURRENT.get() != this) {
            throw new IllegalStateException("Physical invocation scopes must close in owner/LIFO order");
        }
        closed = true;
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
        if (interrupted) {
            if (previous == null) Thread.currentThread().interrupt();
            else previous.interrupted = true;
        }
    }

    /**
     * 保留 invokeAll 的结果契约；异常返回之前等待已开始的 JDBC 调用退出。
     * 不设置新的执行线程或队列，物理占用仍归属原有有界调用方。
     */
    static <T> List<Future<T>> invokeAll(ExecutorService executor,
            Collection<? extends Callable<T>> tasks) throws InterruptedException {
        List<TrackedCall<T>> tracked = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) tracked.add(new TrackedCall<>(task));
        try {
            return executor.invokeAll(tracked);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // 先统一废弃未开始任务，不能等待第一个任务时允许后续旧任务启动。
            for (TrackedCall<T> task : tracked) task.abandonPending();
            boolean interrupted = Thread.interrupted();
            for (TrackedCall<T> task : tracked) interrupted |= task.awaitExit();
            if (interrupted) {
                PhysicalInvocationScope scope = CURRENT.get();
                if (scope == null) Thread.currentThread().interrupt();
                else scope.interrupted = true;
            }
        }
    }

    /** 互斥状态将“尚未开始”与“正在执行”区分，Future.cancel 不参与退出判定。 */
    private static final class TrackedCall<T> implements Callable<T> {
        private final Callable<T> task;
        private boolean started;
        private boolean settled;

        private TrackedCall(Callable<T> task) {
            this.task = task;
        }

        @Override
        public T call() throws Exception {
            synchronized (this) {
                if (settled) return null;
                started = true;
            }
            try (PhysicalInvocationScope ignored = PhysicalInvocationScope.open()) {
                return task.call();
            } finally {
                synchronized (this) {
                    settled = true;
                    notifyAll();
                }
            }
        }

        private synchronized void abandonPending() {
            if (!started) settled = true;
        }

        private synchronized boolean awaitExit() {
            boolean interrupted = false;
            while (!settled) {
                try { wait(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            return interrupted;
        }
    }
}
