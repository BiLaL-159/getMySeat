/**
 * Access module: who the caller is and what they may do. Owns the resource-server security setup and the
 * {@link com.getmyseat.access.Caller} abstraction. Other modules take a {@code Caller} controller
 * parameter instead of reading tokens.
 */
package com.getmyseat.access;
