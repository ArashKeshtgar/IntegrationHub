package ca.arashkeshtgar.integrationhub;

import ca.arashkeshtgar.integrationhub.hl7.Mllp;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class Hl7TestSupport {

  /** 32 zero-ish bytes, base64: only for tests. */
  static final String QUEUE_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

  private Hl7TestSupport() {}

  static String sample(String name) {
    try (InputStream in = Hl7TestSupport.class.getResourceAsStream("/hl7/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A sample with its MSH-10 replaced, so each test sends a message the hub has not seen. */
  static String sample(String name, String controlId) {
    String text = sample(name);
    int start = text.indexOf("MSG");
    int end = text.indexOf('|', start);
    return text.substring(0, start) + controlId + text.substring(end);
  }

  /** One message on a new connection; returns the ACK text. */
  static String mllp(int port, String message) throws IOException {
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(15_000);
      Mllp.write(socket.getOutputStream(), message.getBytes(StandardCharsets.ISO_8859_1));
      byte[] ack = Mllp.read(socket.getInputStream(), 1 << 20);
      return ack == null ? null : new String(ack, StandardCharsets.ISO_8859_1);
    }
  }

  static String ackCode(String ack) {
    for (String segment : ack.split("\r")) {
      if (segment.startsWith("MSA|")) {
        return segment.split("\\|", -1)[1];
      }
    }
    return null;
  }

  static String ackText(String ack) {
    for (String segment : ack.split("\r")) {
      if (segment.startsWith("MSA|")) {
        return segment.split("\\|", -1)[3];
      }
    }
    return null;
  }
}
