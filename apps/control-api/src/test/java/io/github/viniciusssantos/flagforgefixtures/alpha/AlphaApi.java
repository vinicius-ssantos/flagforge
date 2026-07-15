package io.github.viniciusssantos.flagforgefixtures.alpha;

import io.github.viniciusssantos.flagforgefixtures.beta.BetaApi;

public final class AlphaApi {

    private final BetaApi betaApi;

    public AlphaApi(BetaApi betaApi) {
        this.betaApi = betaApi;
    }

    public BetaApi betaApi() {
        return betaApi;
    }
}
