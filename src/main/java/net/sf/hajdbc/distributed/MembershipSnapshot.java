package net.sf.hajdbc.distributed;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 同一次读取的成员视图与精确目标集合，用于恢复协议确认，不能以 RPC 返回集合替代。
 * 视图及成员身份使用序列化内容摘要，不依赖可能重复的 JGroups 显示名称。
 */
public final class MembershipSnapshot implements Serializable {
  private static final long serialVersionUID = 1L;
  private final String viewId;
  private final Set<Member> members;

  public MembershipSnapshot(Member creator, long viewSequence, Collection<? extends Member> members) {
    if (creator == null || members == null || members.isEmpty() || members.contains(null))
      throw new IllegalArgumentException("A complete membership view is required");
    this.viewId = memberId(creator) + ":" + viewSequence;
    this.members = Collections.unmodifiableSet(new HashSet<>(members));
    if (this.members.size() != members.size()) throw new IllegalArgumentException("Duplicate member identity");
  }

  public String getViewId() { return viewId; }
  public Set<Member> getMembers() { return members; }

  /** 成员进程身份；重启后的新逻辑地址不能沿用旧成员资格。 */
  public static String memberId(Member member) {
    if (member == null) throw new IllegalArgumentException("Member is required");
    return digest(member);
  }

  private static String digest(Object value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(net.sf.hajdbc.util.Objects.serialize(value));
      StringBuilder result = new StringBuilder(64);
      for (byte b : bytes) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
      return result.toString();
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is required", e); }
  }

  @Override public boolean equals(Object value) {
    if (!(value instanceof MembershipSnapshot)) return false;
    MembershipSnapshot other = (MembershipSnapshot) value;
    return viewId.equals(other.viewId) && members.equals(other.members);
  }
  @Override public int hashCode() { return 31 * viewId.hashCode() + members.hashCode(); }
}
