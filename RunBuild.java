import java.io.*;
import java.nio.file.*;

public class RunBuild {
  public static void main(String[] args) throws Exception {
    // Try to run Maven with spotless
    ProcessBuilder pb = new ProcessBuilder(
        "/usr/bin/mvnd", "spotless:apply", "-q"
    );
    pb.directory(new File("/home/user/jotp"));
    pb.inheritIO();

    int rc = pb.start().waitFor();
    if (rc != 0) {
      System.out.println("Spotless returned: " + rc);
    } else {
      System.out.println("Spotless formatting applied successfully");
    }

    // Now run tests
    pb = new ProcessBuilder(
        "/usr/bin/mvnd", "test", "-Dtest=CircuitBreakerTest", "-q"
    );
    pb.directory(new File("/home/user/jotp"));
    pb.inheritIO();

    rc = pb.start().waitFor();
    System.out.println("Tests returned: " + rc);
  }
}
