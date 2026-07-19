package com.enrichmeai.cistern.core;

/** The in-memory reference backend must pass the full contract kit (T1.2 DoD). */
class InMemoryResourceStoreTest extends ResourceStoreContractTest {

    @Override
    protected ResourceStore newStore() {
        return new InMemoryResourceStore();
    }
}
