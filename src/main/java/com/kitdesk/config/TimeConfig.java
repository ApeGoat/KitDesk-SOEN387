package com.kitdesk.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's clock. "Today" means today at the desk (Montreal time by default), so a booking
 * date doesn't flip at 7 pm because the server runs on UTC. Services take the clock as a bean so
 * tests could fix the time if needed.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock(@Value("${kitdesk.timezone:America/Toronto}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
