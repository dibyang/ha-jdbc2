package net.sf.hajdbc.dialect.h2;

import java.io.IOException;
import java.util.Set;
import net.sf.hajdbc.lock.WriteLock;

/**
 * 将恢复激活意向与真实全局写锁绑定。未知决定不释放锁，重启先重建锁再恢复活动库。
 * 每个参与者使用一个实例；普通业务锁不走本类。调用方管理意向文件生命周期。
 */
public final class H2RestoreWriteFence {
  private final H2ActivationIntent intent;
  private final WriteLock lock;
  private volatile boolean held;
  private volatile boolean needsLocalApply;

  /** 启动时若发现未决意向立即恢复本地写保护；失败则禁止集群继续启动。 */
  public H2RestoreWriteFence(H2ActivationIntent intent, WriteLock lock) throws IOException {
    this.intent = intent;
    this.lock = lock;
    H2ActivationIntent.Snapshot record = intent.snapshot();
    needsLocalApply = record.decision != H2ActivationIntent.Decision.NONE;
    if (intent.requiresFence() || needsLocalApply) acquire();
  }

  private H2RestoreWriteFence(H2ActivationIntent intent, WriteLock lock, boolean routeAlreadyApplied) {
    this.intent = intent; this.lock = lock;
    this.needsLocalApply = !routeAlreadyApplied;
  }

  /** 仅在当前进程已收口后更换描述符对应的锁；重启必须走正常构造恢复路由。 */
  public synchronized H2RestoreWriteFence nextOperation(WriteLock nextLock) throws IOException {
    if (held || isUnresolved()) throw new IOException("Previous recovery is unresolved");
    return new H2RestoreWriteFence(intent, nextLock, true);
  }

  /** 持锁并持久准备后才允许应答；同一已完成操作不能重新取得写保护。 */
  public synchronized H2ActivationIntent.Snapshot prepare(long epoch, String operation, String owner,
      String digest, Set<String> participants) throws IOException {
    return prepare(epoch, operation, owner, owner, "local", digest, participants);
  }

  /** 准备时绑定发起节点稳定身份和视图，源节点重启后可以识别自己的未决决定。 */
  public synchronized H2ActivationIntent.Snapshot prepare(long epoch, String operation, String owner,
      String ownerDatabase, String membership, String digest, Set<String> participants) throws IOException {
    return prepare(epoch, operation, owner, ownerDatabase, membership, digest, participants, java.util.Collections.emptySet());
  }
  public synchronized H2ActivationIntent.Snapshot prepare(long epoch, String operation, String owner,
      String ownerDatabase, String membership, String digest, Set<String> participants, Set<String> databases) throws IOException {
    H2ActivationIntent.Snapshot previous = intent.snapshot();
    if (previous.epoch == epoch && previous.operation.equals(operation) && previous.finished) {
      intent.prepare(epoch, operation, owner, ownerDatabase, membership, digest, participants, databases);
      return intent.snapshot();
    }
    if (needsLocalApply && previous.finished) throw new IOException("Previous recovery route must be rebuilt first");
    boolean acquired = !held;
    if (acquired) acquire();
    try {
      intent.prepare(epoch, operation, owner, ownerDatabase, membership, digest, participants, databases);
      needsLocalApply = true;
      return intent.snapshot();
    } catch (IOException | RuntimeException | Error failure) {
      if (acquired && !intent.requiresFence()) release();
      throw failure;
    }
  }

  /**
   * 原子顺序：决定落盘 → 应用本地路由/隔离状态 → 完成落盘 → 释放写保护。
   * action 只做本地、幂等的路由变更；对端确认和决定来源核验由调用方提前完成。
   */
  public synchronized void decideAndApply(long epoch, String operation, H2ActivationIntent.Decision decision,
      LocalRouteChange action) throws Exception {
    decideAndApply(epoch, operation, decision, intent.snapshot().restoredGeneration, action);
  }

  /** 首次提交必须显式提供目标恢复成功代次；重复决定也须保持同一代次。 */
  public synchronized void decideAndApply(long epoch, String operation, H2ActivationIntent.Decision decision,
      long restoredGeneration, LocalRouteChange action) throws Exception {
    if (decision == H2ActivationIntent.Decision.COMMIT) intent.commit(epoch, operation, restoredGeneration);
    else intent.decide(epoch, operation, decision);
    H2ActivationIntent.Snapshot record = intent.snapshot();
    if (!record.finished || needsLocalApply) {
      if (!held) acquire();
      action.apply();
      intent.applied(epoch, operation);
      needsLocalApply = false;
    }
    if (!intent.requiresFence()) release();
  }

  /** 成员离开或迟到 unlock 只请求释放；未决/未应用/文件故障一律保留保护。 */
  public synchronized boolean requestRelease(long epoch, String operation) throws IOException {
    H2ActivationIntent.Snapshot record = intent.snapshot();
    if (record.epoch != epoch || !record.operation.equals(operation) || intent.requiresFence() || needsLocalApply) return false;
    release();
    return true;
  }

  // 管理器在自己的注册锁内读取；不能反向等待正执行本地路由回调的 fence 监视器。
  public boolean isHeld() { return held; }
  public boolean isUnresolved() { return intent.requiresFence() || needsLocalApply; }

  private void acquire() throws IOException {
    if (!lock.tryLock()) throw new IOException("Unable to acquire H2 recovery write fence");
    held = true;
  }

  private void release() {
    if (held) {
      lock.unlock();
      held = false;
    }
  }

  /** 激活/撤销的本地幂等路由操作，失败后保留写保护供自动核对重试。 */
  @FunctionalInterface
  public interface LocalRouteChange { void apply() throws Exception; }
}
