package com.paytm.seats.reservation;

/** {@link ReservationConcurrencyTests} with every fast-decline layer disabled: the DB alone decides. */
@LayersOff
class ReservationConcurrencyLayersOffTests extends ReservationConcurrencyTests {

}
