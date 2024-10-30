package net.sf.hajdbc.exception;

public class HaJdbcException extends Exception{
  public HaJdbcException() {
  }

  public HaJdbcException(String message) {
    super(message);
  }

  public HaJdbcException(String message, Throwable cause) {
    super(message, cause);
  }

  public HaJdbcException(Throwable cause) {
    super(cause);
  }

  public HaJdbcException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
