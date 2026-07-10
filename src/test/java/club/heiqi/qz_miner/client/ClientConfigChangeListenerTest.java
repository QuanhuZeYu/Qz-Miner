package club.heiqi.qz_miner.client;

import java.io.File;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.ConfigChangeEvent;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.qz_miner.config.ConfigSemanticValidator;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;

/** ConfigManager 与 listener 实例的精确订阅生命周期。 */
public class ClientConfigChangeListenerTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-listener-").toFile();
        ClientConfigChangeListener.resetSubscriptionForTests();
    }

    @After
    public void tearDown() {
        ClientConfigChangeListener.resetSubscriptionForTests();
        deleteRecursively(tempDir);
    }

    @Test
    public void replacingManagerUnsubscribesExactOldListener() throws Exception {
        ConfigManager firstManager = newManager("first.yaml");
        ConfigManager secondManager = newManager("second.yaml");
        CountingListener first = new CountingListener(firstManager);
        CountingListener second = new CountingListener(secondManager);

        first.register();
        second.register();
        saveRadius(firstManager, 21);
        saveRadius(secondManager, 22);

        Assert.assertEquals("old manager save must not invoke stale listener", 0, first.batchSaveCount);
        Assert.assertEquals(1, second.batchSaveCount);
    }

    @Test
    public void replacingListenerOnSameManagerAndRepeatRegisterNeverDuplicates() throws Exception {
        ConfigManager manager = newManager("same.yaml");
        CountingListener first = new CountingListener(manager);
        CountingListener second = new CountingListener(manager);

        first.register();
        second.register();
        second.register();
        saveRadius(manager, 23);

        Assert.assertEquals(0, first.batchSaveCount);
        Assert.assertEquals("same listener repeated register must stay single", 1, second.batchSaveCount);
    }

    private ConfigManager newManager(String name) throws Exception {
        return ConfigManager.bootstrap(new File(tempDir, name), QzMinerConfigSchema.create(),
                ConfigSemanticValidator.draftValidator());
    }

    private static void saveRadius(ConfigManager manager, int radius) {
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(radius));
        Assert.assertTrue(manager.save(draft).isSuccess());
    }

    private static final class CountingListener extends ClientConfigChangeListener {
        private int batchSaveCount;

        private CountingListener(ConfigManager manager) {
            super(manager);
        }

        @Override
        public void onConfigChanged(ConfigChangeEvent event) {
            if (event != null && event.getType() == ConfigChangeEvent.ChangeType.BATCH_SAVE) {
                batchSaveCount++;
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
