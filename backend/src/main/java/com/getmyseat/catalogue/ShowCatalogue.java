package com.getmyseat.catalogue;

import java.util.Optional;
import java.util.UUID;

/** The catalogue's read port for other modules: a Show as something to sell. */
public interface ShowCatalogue {

	/** The Show, draft or published; empty if there's no Show with that id. */
	Optional<SellableShow> sellableShow(UUID id);

}
