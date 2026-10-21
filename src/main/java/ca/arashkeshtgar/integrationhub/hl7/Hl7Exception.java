package ca.arashkeshtgar.integrationhub.hl7;

/**
 * A message that cannot be accepted. {@code reason} is a short phrase safe to write down and to
 * send back in an ACK: it names what is wrong, never what the message contained.
 */
public class Hl7Exception extends Exception {

  public Hl7Exception(String reason) {
    super(reason);
  }
}
