package io.github.viniciusssantos.flagforgefixtures.beta;

import io.github.viniciusssantos.flagforgefixtures.alpha.AlphaApi;
import io.github.viniciusssantos.flagforgefixtures.alpha.internal.AlphaInternal;

public final class BetaApi {

    private final AlphaApi alphaApi;
    private final AlphaInternal alphaInternal;

    public BetaApi(AlphaApi alphaApi, AlphaInternal alphaInternal) {
        this.alphaApi = alphaApi;
        this.alphaInternal = alphaInternal;
    }

    public AlphaApi alphaApi() {
        return alphaApi;
    }

    public AlphaInternal alphaInternal() {
        return alphaInternal;
    }
}
