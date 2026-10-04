/**
 * Who is calling.
 * <ul>
 * <li>Users: HS256 JWT in {@code Authorization: Bearer}; the {@code sub} claim is the
 * user id. Controllers receive it as an {@link com.paytm.seats.auth.AuthenticatedUser}
 * parameter and never read a user id from a request body.</li>
 * <li>Admin: {@code X-Admin-Key} on {@code POST /shows} and
 * {@code POST /auth/tokens}.</li>
 * </ul>
 * There is no user store: any id with a valid token is a user.
 */
package com.paytm.seats.auth;
