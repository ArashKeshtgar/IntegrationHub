package ca.arashkeshtgar.integrationhub.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An HL7 v2 message in "pipe and hat" form, read without a version-specific model so that 2.3,
 * 2.3.1, 2.4 and 2.5 all parse the same way. Segments end with CR (LF and CRLF are accepted).
 * Nothing in this class logs or prints content.
 */
public final class Hl7Message {

  public static final int MAX_SEGMENTS = 5000;

  private final char fieldSeparator;
  private final char componentSeparator;
  private final char repetitionSeparator;
  private final char escape;
  private final char subcomponentSeparator;
  private final List<Segment> segments;

  private Hl7Message(String encoding, char fieldSeparator, List<Segment> segments) {
    this.fieldSeparator = fieldSeparator;
    this.componentSeparator = encoding.length() > 0 ? encoding.charAt(0) : '^';
    this.repetitionSeparator = encoding.length() > 1 ? encoding.charAt(1) : '~';
    this.escape = encoding.length() > 2 ? encoding.charAt(2) : '\\';
    this.subcomponentSeparator = encoding.length() > 3 ? encoding.charAt(3) : '&';
    this.segments = segments;
  }

  /** One segment: its name and its fields as written (not unescaped). */
  public final class Segment {
    private final String name;
    private final List<String> parts;

    private Segment(String name, List<String> parts) {
      this.name = name;
      this.parts = parts;
    }

    public String name() {
      return name;
    }

    /** Field {@code n} (1-based, as in the standard: {@code MSH-9} is {@code field(9)}), raw. */
    public String field(int n) {
      if (name.equals("MSH")) {
        if (n == 1) {
          return String.valueOf(fieldSeparator);
        }
        if (n == 2) {
          return parts.size() > 1 ? parts.get(1) : "";
        }
        return n - 1 < parts.size() ? parts.get(n - 1) : "";
      }
      return n < parts.size() ? parts.get(n) : "";
    }

    /**
     * Component {@code c} (1-based) of the first repetition of field {@code n}, first subcomponent,
     * unescaped.
     */
    public String component(int n, int c) {
      return unescape(sub(rawComponent(n, c)));
    }

    /** The same, but the whole component with its subcomponents, unescaped. */
    public String componentText(int n, int c) {
      return unescape(rawComponent(n, c));
    }

    /** Component {@code c}, subcomponent {@code s} (both 1-based), unescaped. */
    public String subcomponent(int n, int c, int s) {
      String[] subs = split(rawComponent(n, c), subcomponentSeparator);
      return s - 1 < subs.length ? unescape(subs[s - 1]) : "";
    }

    /** All repetitions of field {@code n}, raw. */
    public List<String> repetitions(int n) {
      String raw = field(n);
      return raw.isEmpty() ? List.of() : List.of(split(raw, repetitionSeparator));
    }

    /**
     * Component {@code c} of repetition {@code r} (0-based) of field {@code n}, first subcomponent,
     * unescaped.
     */
    public String componentOf(String repetition, int c) {
      String[] comps = split(repetition, componentSeparator);
      return c - 1 < comps.length ? unescape(sub(comps[c - 1])) : "";
    }

    public String unescapedField(int n) {
      return unescape(field(n));
    }

    private String rawComponent(int n, int c) {
      String first = repetitions(n).stream().findFirst().orElse("");
      String[] comps = split(first, componentSeparator);
      return c - 1 < comps.length ? comps[c - 1] : "";
    }

    private String sub(String raw) {
      int at = raw.indexOf(subcomponentSeparator);
      return at < 0 ? raw : raw.substring(0, at);
    }
  }

  public char componentSeparator() {
    return componentSeparator;
  }

  public List<Segment> segments() {
    return segments;
  }

  public Optional<Segment> first(String name) {
    return segments.stream().filter(s -> s.name.equals(name)).findFirst();
  }

  public List<Segment> all(String name) {
    return segments.stream().filter(s -> s.name.equals(name)).toList();
  }

  /**
   * Parses one message. Throws {@link Hl7Exception} with a reason that quotes nothing of the
   * content.
   */
  public static Hl7Message parse(String text) throws Hl7Exception {
    String normalised = text.replace("\r\n", "\r").replace('\n', '\r');
    List<String> lines = new ArrayList<>();
    for (String line : normalised.split("\r")) {
      String trimmed = stripFraming(line);
      if (!trimmed.isEmpty()) {
        lines.add(trimmed);
      }
    }
    if (lines.isEmpty() || !lines.get(0).startsWith("MSH") || lines.get(0).length() < 4) {
      throw new Hl7Exception("message does not start with an MSH segment");
    }
    if (lines.size() > MAX_SEGMENTS) {
      throw new Hl7Exception("too many segments");
    }
    char separator = lines.get(0).charAt(3);
    if (Character.isLetterOrDigit(separator) || separator == '\r') {
      throw new Hl7Exception("invalid field separator");
    }
    String[] msh = split(lines.get(0), separator);
    String encoding = msh.length > 1 ? msh[1] : "";
    if (encoding.length() < 3) {
      throw new Hl7Exception("invalid encoding characters");
    }
    Hl7Message message = new Hl7Message(encoding, separator, new ArrayList<>());
    for (String line : lines) {
      if (line.length() < 3 || !line.substring(0, 3).matches("[A-Z][A-Z0-9]{2}")) {
        throw new Hl7Exception("invalid segment name");
      }
      if (line.length() > 3 && line.charAt(3) != separator) {
        throw new Hl7Exception("invalid segment name");
      }
      String[] parts = split(line, separator);
      message.segments.add(message.new Segment(parts[0], List.of(parts)));
    }
    return message;
  }

  private static String stripFraming(String line) {
    int start = 0;
    int end = line.length();
    while (start < end && (line.charAt(start) == 0x0B || line.charAt(start) == ' ')) {
      start++;
    }
    while (end > start && (line.charAt(end - 1) == 0x1C || line.charAt(end - 1) == ' ')) {
      end--;
    }
    return line.substring(start, end);
  }

  private static String[] split(String text, char separator) {
    List<String> out = new ArrayList<>();
    int from = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == separator) {
        out.add(text.substring(from, i));
        from = i + 1;
      }
    }
    out.add(text.substring(from));
    return out.toArray(new String[0]);
  }

  /**
   * Resolves HL7 escape sequences: {@code \F\ \S\ \T\ \R\ \E\}, {@code \.br\} (a line break),
   * {@code \Xhh..\} (hex bytes read as ISO-8859-1) and drops the highlighting and character set
   * switches ({@code \H\ \N\ \C..\ \M..\}).
   */
  public String unescape(String raw) {
    if (raw.indexOf(escape) < 0) {
      return raw;
    }
    StringBuilder out = new StringBuilder(raw.length());
    int i = 0;
    while (i < raw.length()) {
      char c = raw.charAt(i);
      if (c != escape) {
        out.append(c);
        i++;
        continue;
      }
      int end = raw.indexOf(escape, i + 1);
      if (end < 0) {
        out.append(c);
        i++;
        continue;
      }
      String code = raw.substring(i + 1, end);
      switch (code) {
        case "F" -> out.append(fieldSeparator);
        case "S" -> out.append(componentSeparator);
        case "T" -> out.append(subcomponentSeparator);
        case "R" -> out.append(repetitionSeparator);
        case "E" -> out.append(escape);
        case ".br" -> out.append('\n');
        case "H", "N" -> {}
        default -> {
          if (code.startsWith("X") && code.length() > 1) {
            for (int k = 1; k + 2 <= code.length(); k += 2) {
              try {
                out.append((char) Integer.parseInt(code.substring(k, k + 2), 16));
              } catch (NumberFormatException e) {
                // a malformed hex escape is dropped
              }
            }
          } else if (code.startsWith("C") || code.startsWith("M") || code.startsWith(".")) {
            // character set switch, or a formatting command such as \.sp\: dropped
          } else {
            out.append(c).append(code).append(c); // unknown: kept as written
          }
        }
      }
      i = end + 1;
    }
    return out.toString();
  }

  /**
   * Escapes text for writing into a field of a message that uses the standard encoding characters.
   */
  public static String escapeStandard(String text) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (char c : text.toCharArray()) {
      switch (c) {
        case '|' -> out.append("\\F\\");
        case '^' -> out.append("\\S\\");
        case '&' -> out.append("\\T\\");
        case '~' -> out.append("\\R\\");
        case '\\' -> out.append("\\E\\");
        case '\n' -> out.append("\\.br\\");
        case '\r' -> {}
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
