/**
 * Zylos cluster-perimeter API gateway.
 *
 * <p>Reactive Spring Cloud Gateway application. Validates ingress tokens via
 * the Zylos security starter and routes to internal services,
 * exchanging the ingress token per-service for audience downscoping.
 */
@NullMarked
package app.zylos.gateway;

import org.jspecify.annotations.NullMarked;
