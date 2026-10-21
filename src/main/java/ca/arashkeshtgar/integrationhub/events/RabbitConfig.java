package ca.arashkeshtgar.integrationhub.events;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The topic exchange, and a durable queue bound to everything on it so that events published before
 * the consumer (Analytics) first starts wait there instead of being dropped. The consumer declares
 * the same queue, durable and without arguments.
 */
@Configuration
@ConditionalOnProperty(name = "hub.events.enabled", havingValue = "true")
class RabbitConfig {

  @Bean
  TopicExchange clinicalEventsExchange(EventsProperties properties) {
    return new TopicExchange(properties.exchange(), true, false);
  }

  @Bean
  Queue clinicalEventsQueue(EventsProperties properties) {
    return QueueBuilder.durable(properties.queue()).build();
  }

  @Bean
  Binding clinicalEventsBinding(Queue clinicalEventsQueue, TopicExchange clinicalEventsExchange) {
    return BindingBuilder.bind(clinicalEventsQueue).to(clinicalEventsExchange).with("#");
  }
}
