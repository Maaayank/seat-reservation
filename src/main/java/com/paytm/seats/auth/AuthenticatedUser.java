package com.paytm.seats.auth;

/**
 * The caller, as proven by the bearer token. Controllers take this as a
 * parameter; they never read a user id from the request body.
 */
public record AuthenticatedUser(String id) {

	static final String REQUEST_ATTRIBUTE = AuthenticatedUser.class.getName();

}
