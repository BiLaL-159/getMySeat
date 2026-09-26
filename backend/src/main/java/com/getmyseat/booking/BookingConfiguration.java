package com.getmyseat.booking;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingConfiguration.HoldProperties.class)
@EnableScheduling
class BookingConfiguration {

	/**
	 * {@code getmyseat.holds.cleanup-interval} is read by {@link HoldCleanup}'s {@code @Scheduled}, not here.
	 * @param holdTime how long a Hold lasts before it expires
	 */
	@ConfigurationProperties("getmyseat.holds")
	record HoldProperties(Duration holdTime) {
	}

	/** Booking reads "now" only from this, so tests can move time on. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
