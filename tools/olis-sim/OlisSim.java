import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * A stand-in for the OLIS receiver, for developing and testing the hub's ORU^R01 export without the real service.
 * It is a simulator only: it accepts MLLP, checks nothing about the message beyond MSH, answers with an ACK and
 * (optionally) keeps each message as a file. It has no connection to Ontario's OLIS and says nothing about what the
 * real service accepts.
 *
 * <pre>
 *   java tools/olis-sim/OlisSim.java [--port 2576] [--bind 127.0.0.1] [--out DIR] [--mode accept|error|reject|silent]
 * </pre>
 *
 * Modes: accept (AA, default), error (AE), reject (AR), silent (reads the message and never answers; the hub
 * times out). Each message is printed as one line without any content: control id, number of OBR and OBX segments.
 * Messages written with --out are synthetic test data; do not point a hub with real results at this tool.
 */
public final class OlisSim {

  private static final int VT = 0x0B;
  private static final int FS = 0x1C;
  private static final int CR = 0x0D;

  public static void main(String[] args) throws Exception {
    int port = 2576;
    String bind = "127.0.0.1";
    Path out = null;
    String mode = "accept";
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--port" -> port = Integer.parseInt(args[++i]);
        case "--bind" -> bind = args[++i];
        case "--out" -> out = Path.of(args[++i]);
        case "--mode" -> mode = args[++i];
        default -> {
          System.err.println("unknown argument " + args[i]);
          System.exit(2);
        }
      }
    }
    if (!mode.matches("accept|error|reject|silent")) {
      System.err.println("--mode must be accept, error, reject or silent");
      System.exit(2);
    }
    if (out != null) {
      Files.createDirectories(out);
    }
    try (ServerSocket server = new ServerSocket(port, 20, InetAddress.getByName(bind))) {
      System.out.println("olis-sim listening on " + bind + ":" + server.getLocalPort() + " mode=" + mode);
      System.out.flush();
      while (true) {
        Socket socket = server.accept();
        final Path dir = out;
        final String answer = mode;
        Thread.ofVirtual().start(() -> serve(socket, dir, answer));
      }
    }
  }

  private static void serve(Socket socket, Path dir, String mode) {
    try (socket) {
      socket.setSoTimeout(60_000);
      InputStream in = socket.getInputStream();
      OutputStream outStream = socket.getOutputStream();
      while (true) {
        String message = read(in);
        if (message == null) {
          return;
        }
        String control = field(message, "MSH", 10);
        int obr = count(message, "OBR");
        int obx = count(message, "OBX");
        System.out.println("received control=" + control + " obr=" + obr + " obx=" + obx + " mode=" + mode);
        System.out.flush();
        if (dir != null) {
          Files.writeString(
              dir.resolve(control.replaceAll("[^A-Za-z0-9._-]", "_") + ".hl7"),
              message,
              StandardCharsets.ISO_8859_1);
        }
        if (mode.equals("silent")) {
          continue;
        }
        String code = switch (mode) {
          case "error" -> "AE";
          case "reject" -> "AR";
          default -> "AA";
        };
        String ack = "MSH|^~\\&|OLIS|OLIS|INTEGRATIONHUB|HUB|"
            + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
            + "||ACK^R01|" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            + "|P|2.5.1\rMSA|" + code + "|" + control + "|simulator\r";
        byte[] bytes = ack.getBytes(StandardCharsets.ISO_8859_1);
        outStream.write(VT);
        outStream.write(bytes);
        outStream.write(FS);
        outStream.write(CR);
        outStream.flush();
      }
    } catch (IOException e) {
      // connection ended
    }
  }

  private static String read(InputStream in) throws IOException {
    int b;
    do {
      b = in.read();
      if (b < 0) {
        return null;
      }
    } while (b != VT);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    while ((b = in.read()) != FS) {
      if (b < 0) {
        return null;
      }
      buffer.write(b);
    }
    in.read(); // the <CR>
    return buffer.toString(StandardCharsets.ISO_8859_1);
  }

  private static String field(String message, String segment, int n) {
    for (String line : message.split("\r")) {
      if (line.startsWith(segment + "|")) {
        String[] parts = line.split("\\|", -1);
        int index = segment.equals("MSH") ? n - 1 : n;
        return index < parts.length ? parts[index] : "";
      }
    }
    return "";
  }

  private static int count(String message, String segment) {
    int n = 0;
    for (String line : message.split("\r")) {
      if (line.startsWith(segment + "|")) {
        n++;
      }
    }
    return n;
  }
}
