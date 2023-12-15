package net.sf.hajdbc.util;


import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;

/**
 * @ClassName FileReader
 * @Description 文件自动读取器
 */
public class FileReader<T> {
  final Logger logger = LoggerFactory.getLogger(this.getClass());

  public static final Function<byte[], Optional<String>> READER4STRING = bytes -> {
    if(bytes!=null) {
      String val = new String(bytes);
      return Optional.ofNullable(val.trim());
    }
    return Optional.empty();
  };
  public static final Function<byte[], Optional<Integer>> READER4INTEGER = bytes -> {
    return READER4STRING.apply(bytes).map(s-> {
      return Integer.parseInt(s);
    });
  };

  public static final Function<byte[], Optional<Long>> READER4LONG = bytes -> {
    return READER4STRING.apply(bytes).map(s->Long.parseLong(s));
  };

  public static final Function<byte[], Optional<Double>> READER4DOUBLE = bytes -> {
    return READER4STRING.apply(bytes).map(s->Double.parseDouble(s));
  };



  /**
   * 要看守的文件
   */
  private final File file;

  /**
   * 文件最后的修改时间
   */
  private long modified = -1;

  private T data;

  /**
   * 文件的读取器
   */
  private final Function<byte[],Optional<T>> reader;

  private FileReader(String name, Function<byte[],Optional<T>> reader) {
    Preconditions.checkNotNull(name);
    Preconditions.checkNotNull(reader);
    this.file = Paths.get("/etc/ha-jdbc", name).toFile();
    this.reader = reader;
  }

  /**
   * 获取配置数据
   * @param defValue 默认值
   * @return
   */
  public synchronized T getData(T defValue) {
    return getData(defValue, defValue);
  }

  /**
   * 获取配置数据
   * @param defValue 默认值
   * @param noSetting 未设置时的默认值
   * @return
   */
  public synchronized T getData(T defValue, T noSetting) {
    boolean readed = false;
    if (this.file.exists()) {
      if(modified!=this.file.lastModified()) {
        this.modified = this.file.lastModified();
        try {
          byte[] bytes = Files.readAllBytes(this.file.toPath());
          if (bytes != null) {
            this.data = this.reader.apply(bytes).orElse(noSetting);
          }
        } catch (Exception e) {
          //ignore e
          this.data = defValue;
        }
        readed = true;
      }
    }else{
      if(this.modified!=0) {
        this.data = noSetting;
        this.modified = 0;
        readed = true;
      }
    }
    if(readed){
      logger.log(Level.INFO, "read config: {0}={1}", this.file.getName(), this.data);
    }
    return data;
  }

  public static <T> FileReader<T> of(String name, Function<byte[],Optional<T>> reader){
    return new FileReader(name, reader);
  }

  public static  FileReader<String> of(String name){
    return of(name, READER4STRING);
  }

  public static  FileReader<Integer> of4int(String name){
    return of(name, READER4INTEGER);
  }
  public static  FileReader<Long> of4long(String name){
    return of(name, READER4LONG);
  }

  public static  FileReader<Double> of4double(String name){
    return of(name, READER4DOUBLE);
  }

  public static <T> FileReader<T> of2(String name, Function<String,T> reader){
    return of(name, bytes -> {
      return READER4STRING.apply(bytes).map(s->reader.apply(s));
    });
  }

  public static <E extends Enum<E>> FileReader<E> of(String name, Class<E> clazz){
    return of2(name, s -> Enum.valueOf(clazz, s));
  }
}
