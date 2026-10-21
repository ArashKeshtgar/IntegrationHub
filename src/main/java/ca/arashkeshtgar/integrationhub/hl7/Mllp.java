package ca.arashkeshtgar.integrationhub.hl7;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** MLLP framing: {@code <VT> message <FS><CR>}. */
public final class Mllp {

  public static final int VT = 0x0B;
  public static final int FS = 0x1C;
  public static final int CR = 0x0D;

  private Mllp() {}

  /** The frame is bigger than allowed, or its end is not {@code <FS><CR>}. */
  public static final class FrameException extends IOException {
    public FrameException(String message) {
      super(message);
    }
  }

  /**
   * Reads the next frame; null at a clean end of stream between frames. Bytes before the start byte
   * (a stray CR or LF after the last frame) are skipped.
   */
  public static byte[] read(InputStream in, int maxBytes) throws IOException {
    int b;
    do {
      b = in.read();
      if (b < 0) {
        return null;
      }
    } while (b != VT);
    ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
    while (true) {
      b = in.read();
      if (b < 0) {
        throw new FrameException("connection closed inside a message");
      }
      if (b == FS) {
        if (in.read() != CR) {
          throw new FrameException("frame does not end with <FS><CR>");
        }
        return out.toByteArray();
      }
      if (b == VT) {
        throw new FrameException("start byte inside a message");
      }
      if (out.size() >= maxBytes) {
        throw new FrameException("message too large");
      }
      out.write(b);
    }
  }

  public static void write(OutputStream out, byte[] message) throws IOException {
    byte[] frame = new byte[message.length + 3];
    frame[0] = VT;
    System.arraycopy(message, 0, frame, 1, message.length);
    frame[frame.length - 2] = FS;
    frame[frame.length - 1] = CR;
    out.write(frame);
    out.flush();
  }
}
