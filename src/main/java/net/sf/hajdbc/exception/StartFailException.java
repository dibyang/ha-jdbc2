package net.sf.hajdbc.exception;

public class StartFailException extends HaJdbcException{

  public static final String MESSAGE = "Can not start this node, try start from another node.";

  public StartFailException() {
    super(MESSAGE);
  }

  public StartFailException(Throwable cause) {
    super(MESSAGE, cause);
  }

  public StartFailException(String message) {
    super(message);
  }

  public StartFailException(String message, Throwable cause) {
    super(message, cause);
  }
}
