package net.sf.hajdbc.lock.distributed;

import net.sf.hajdbc.distributed.Member;
import java.util.Objects;

/**
 * H2 恢复专用锁身份。与旧通用锁描述符分离，迟到释放不能命中新一轮或普通业务锁。
 * 只走新版本恢复命令；既有锁命令序列化结构保持不变。
 */
public final class RestoreLockDescriptor implements RemoteLockDescriptor {
  private static final long serialVersionUID = 1L;
  private final Member member;
  private final String database;
  private final String operation;
  private final long epoch;

  public RestoreLockDescriptor(Member member, String database, long epoch, String operation) {
    if (member == null || database == null || database.isEmpty() || operation == null || operation.isEmpty() || epoch <= 0)
      throw new IllegalArgumentException("Invalid restore lock identity");
    this.member = member; this.database = database; this.operation = operation; this.epoch = epoch;
  }
  @Override public Member getMember() { return member; }
  @Override public String getId() { return null; }
  @Override public LockType getType() { return LockType.WRITE; }
  public String getDatabase() { return database; }
  public String getOperation() { return operation; }
  public long getEpoch() { return epoch; }
  @Override public boolean equals(Object value) {
    if (!(value instanceof RestoreLockDescriptor)) return false;
    RestoreLockDescriptor other = (RestoreLockDescriptor) value;
    return epoch == other.epoch && member.equals(other.member) && database.equals(other.database) && operation.equals(other.operation);
  }
  @Override public int hashCode() { return Objects.hash(member, database, epoch, operation); }
  @Override public String toString() { return "H2RestoreLock[" + database + "," + epoch + "," + operation + "]"; }
}
