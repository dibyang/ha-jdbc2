package net.sf.hajdbc.balancer;

import net.sf.hajdbc.Database;

public interface DatabaseChecker {
  boolean isValid(Database<?> database);
}
