package ca.arashkeshtgar.integrationhub.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Who may read the message log and the metrics: a ClinicHub token with {@code permission}. {@code
 * localAccess} (the dev profile) also lets a call from this machine through without a token; it is
 * ignored for any call that carries a forwarding header, so a proxy in front never makes everyone
 * "local".
 */
@ConfigurationProperties("hub.admin")
public record AdminProperties(
    @DefaultValue("integrationhub:messages.view") String permission,
    @DefaultValue("") String audience,
    @DefaultValue("false") boolean localAccess) {}
