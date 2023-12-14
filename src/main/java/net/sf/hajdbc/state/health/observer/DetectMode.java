package net.sf.hajdbc.state.health.observer;

/**
 * 检测模式
 */
public enum DetectMode {
  /**
   * 禁用
   */
  disabled,
  /**
   * 需要全部满足
   */
  all,
  /**
   * 需要过半满足
   */
  half;

  public static DetectMode of(String name){
    DetectMode mode = DetectMode.disabled;
    for (DetectMode detectMode : DetectMode.values()) {
      if(detectMode.name().equalsIgnoreCase(name)){
        mode = detectMode;
        break;
      }
    }
    return mode;

  }

}
