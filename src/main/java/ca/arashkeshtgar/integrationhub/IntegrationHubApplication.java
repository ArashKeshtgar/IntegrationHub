package ca.arashkeshtgar.integrationhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IntegrationHubApplication {

  public static void main(String[] args) {
    SpringApplication.run(IntegrationHubApplication.class, args);
  }
}
