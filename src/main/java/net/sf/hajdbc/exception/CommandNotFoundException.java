package net.sf.hajdbc.exception;

public class CommandNotFoundException extends RuntimeException{
  private final String command;

  public String getCommand() {
    return command;
  }

  public CommandNotFoundException(String command) {
    this(command, null);
  }

  public CommandNotFoundException(String command, Throwable cause) {
    super(command + " is not found.", cause);
    this.command = command;
  }
}
