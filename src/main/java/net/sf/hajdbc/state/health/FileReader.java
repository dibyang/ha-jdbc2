package net.sf.hajdbc.state.health;


import net.sf.hajdbc.util.Preconditions;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Function;

/**
 * @ClassName FileReader
 * @Description 文件自动读取器
 */
public class FileReader<T> {
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
  private long modified = 0;

  private Optional<T> data;

  /**
   * 文件的读取器
   */
  private final Function<byte[],Optional<T>> reader;

  private FileReader(File file, Function<byte[],Optional<T>> reader) {
    Preconditions.checkNotNull(file);
    Preconditions.checkNotNull(reader);
    this.file = file;
    this.reader = reader;
  }

  public synchronized Optional<T> getData(T defValue) {
    if (this.file.exists()) {
      this.modified = this.file.lastModified();
      try {
        byte[] bytes = Files.readAllBytes(this.file.toPath());
        if (bytes != null) {
          this.data = this.reader.apply(bytes);
        }
      } catch (Exception e) {
        //ignore e
        this.data = Optional.ofNullable(defValue);
      }
    }else{
      this.modified = 0;
      this.data = Optional.empty();
    }
    return data;
  }

  public static <T> FileReader<T> of(File file, Function<byte[],Optional<T>> reader){
    return new FileReader<>(file, reader);
  }

  public static <T> FileReader<T> of(Path path, Function<byte[],Optional<T>> reader){
    return of(path.toFile(), reader);
  }

  public static <T> FileReader<T> of(String path, Function<byte[],Optional<T>> reader){
    return of(Paths.get(path), reader);
  }

  public static  FileReader<String> of(File file){
    return of(file, READER4STRING);
  }

  public static  FileReader<Integer> of4int(File file){
    return of(file, READER4INTEGER);
  }
  public static  FileReader<Long> of4long(File file){
    return of(file, READER4LONG);
  }

  public static  FileReader<Double> of4double(File file){
    return of(file, READER4DOUBLE);
  }


  public static  FileReader<String> of(Path path){
    return of(path, READER4STRING);
  }

  public static  FileReader<Integer> of4int(Path path){
    return of(path, READER4INTEGER);
  }
  public static  FileReader<Long> of4long(Path path){
    return of(path, READER4LONG);
  }

  public static  FileReader<Double> of4double(Path path){
    return of(path, READER4DOUBLE);
  }

  public static  FileReader<String> of(String path){
    return of(path, READER4STRING);
  }

  public static  FileReader<Integer> of4int(String path){
    return of(path, READER4INTEGER);
  }
  public static  FileReader<Long> of4long(String path){
    return of(path, READER4LONG);
  }

  public static  FileReader<Double> of4double(String path){
    return of(path, READER4DOUBLE);
  }

  public static <T> FileReader<T> of2(String path, Function<String,T> reader){
    return of(path, bytes -> {
      return READER4STRING.apply(bytes).map(s->reader.apply(s));
    });
  }

  public static <E extends Enum<E>> FileReader<E> of(String path, Class<E> clazz){
    return of2(path, s -> Enum.valueOf(clazz, s));
  }
}
