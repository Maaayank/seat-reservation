package com.paytm.seats.reservation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs a test class with all fast-decline layers disabled, proving the DB transaction
 * alone is race-free (docs/DISCOVERY.md D15).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = { "seats.layers.sold-set=false", "seats.layers.read-check=false",
		"seats.layers.seat-claim=false" })
@interface LayersOff {

}
