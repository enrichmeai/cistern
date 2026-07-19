package com.enrichmeai.cistern.storage.file;

import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.ResourceStoreContractTest;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The file backend must pass the shared contract kit unchanged (T1.3 DoD). */
class FileResourceStoreContractTest extends ResourceStoreContractTest {

    @TempDir
    Path tempDir;

    @Override
    protected ResourceStore newStore() {
        return new FileResourceStore(tempDir);
    }
}
