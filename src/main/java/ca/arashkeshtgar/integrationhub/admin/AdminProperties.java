package ca.arashkeshtgar.integrationhub.admin;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Who may read the message log, the metrics and the admin API, and who may replay: a ClinicHub
 * token with {@code permission} (read) or {@code replayPermission} (replay a queued HL7 message).
 * ClinicHub hands an application its permissions without the application's prefix, so {@code
 * messages.view} is accepted as well as {@code integrationhub:messages.view}. {@code localAccess}
 * (the dev profile) also lets a call from this machine through without a token; it is ignored for
 * any call that carries a forwarding header, so a proxy in front never makes everyone "local".
 * {@code corsOrigins} are the pages (the admin web) that may call the API from a browser.
 */
@ConfigurationProperties("hub.admin")
public record AdminProperties(
    @DefaultValue("integrationhub:messages.view") String permission,
    @DefaultValue("integrationhub:messages.replay") String replayPermission,
    @DefaultValue("") String audience,
    @DefaultValue("false") boolean localAccess,
    @DefaultValue List<String> corsOrigins) {}
