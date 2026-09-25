package com.getmyseat.catalogue;

import java.util.UUID;

/**
 * A Show went on sale. Raised in process, inside the transaction that publishes it, so synchronous listeners run
 * in that transaction and a failing listener undoes the publish.
 */
public record ShowPublished(UUID showId) {
}
